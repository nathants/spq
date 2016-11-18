(ns spq.server
  (:gen-class)
  (:require [compojure
             [core :as compojure :refer [GET POST]]
             [route :as route]]
            [confs.core :as confs :refer [conf]]
            [durable-queue :as dq]
            [manifold.time :as time]
            [spq
             [http :as http :refer [defhandler]]
             [lib :as lib]]
            [taoensso.timbre :as timbre]))

;; TODO consider throttling incoming requests. so that if greather
;; than n requests are active at a time, new ones are established, but
;; get queues up for actual work. and if that queue fills up too, then
;; requests are bounced to the caller with a meaningful status code
;; telling them to retry in a few seconds or something.

(def queue)
(def state)

(defhandler get-status
  [req]
  {:status 200
   :body (lib/json-dumps
          {:headers {:status (:status (:headers req))}
           :query-params (:params req)})})

(defhandler post-status
  [req]
  {:status 200
   :body (lib/json-dumps
          {:body (:body req)
           :headers {:status (:status (:headers req))}
           :query-params (:params req)})})

(defn -swap-retry
  [m id task]
  (-> m
    (update-in [:tasks] dissoc id)
    (assoc-in [:retries task] id)))

(defhandler post-retry
  [req]
  (let [id (:body req)]
    (if-let [task (get-in @state [:tasks id :task])]
      (do (dq/retry! task)
          (swap! state -swap-retry id task)
          (timbre/debug "retry!" id (lib/abbreviate (lib/deref-task task)))
          {:status 200
           :body (str "marked retry for task with id: " id)})
      {:status 204
       :body (str "no such task to complete with id: " id
                  ". either it has already been completed, "
                  "or the server crashed and it will be "
                  "be retried anyway because it was never "
                  "completed.")})))

(defhandler post-complete
  [req]
  (let [id (:body req)]
    (if-let [task (get-in @state [:tasks id :task])]
      (do (dq/complete! task)
          ;; TODO we could potentially delay dissocs, and batch them
          ;; up to reduce swap contention. does it even matter?
          (swap! state #(-> %
                          (update-in [:retries] dissoc task)
                          (update-in [:tasks] dissoc id)))
          (timbre/debug "complete!" id (lib/abbreviate (lib/deref-task task)))
          {:status 200
           :body (str "completed for task with id: " id)})
      {:status 204
       :body (str "no such task to complete with id: " id
                  ". either it has already been completed, "
                  "or the server crashed and it will be "
                  "taken again by another caller.")})))

(defn -swap-take!
  [state task]
  (loop [id (or (get-in @state [:retries task])
                (str (hash task)))
         i 0]
    (condp = (try
               (swap! state (fn [m]
                              (assert (not (contains? m id)))
                              (assoc-in m [:tasks id] {:task task
                                                       :time (System/nanoTime)})))
               (catch AssertionError ex
                 (timbre/debug "task-id collission, looping. count:" i id)
                 (if (> i 1000)
                   (timbre/error "failed to find unique id for task, this should never happen")
                   (lib/shutdown))
                 ::fail))
      ::fail (recur (str (hash (Object.))) (inc i))
      id)))

(defhandler post-take
  [req]
  (let [queue-name (:queue (:params req))
        default (str (conf :server :take-timeout-millis))
        timeout (Long/parseLong (get (:params req) :timeout-ms default))
        task (dq/take! queue queue-name timeout ::empty)]
    (if (= ::empty task)
      (do (timbre/debug "nothing to take for queue:" queue-name)
          {:status 204
           :body "no items available to take"})
      (let [item (lib/deref-task task)
            id (-swap-take! state task)]
        (timbre/debug "take!" queue-name item)
        {:status 200
         :headers {:id id}
         :body (lib/json-dumps item)}))))

(defhandler post-put
  [req]
  (let [queue-name (:queue (:params req))
        item (lib/json-loads (:body req))]
    (dq/put! queue queue-name item)
    (timbre/debug "put!" queue-name item)
    {:status 200}))

(defhandler get-stats
  [req]
  {:status 200
   :body (->> queue
           dq/stats
           (reduce-kv #(assoc %1 %2 {:num-queued (- (:enqueued %3) (:completed %3))
                                     :num-active (:in-progress %3)})
                      {})
           lib/json-dumps)})

(defn -swap-retries
  [m to-retry]
  (reduce (fn [m [id {:keys [task]}]]
            (-swap-retry m id task))
          m
          to-retry))

(defn -minutes-ago
  [{:keys [time]}]
  (-> (System/nanoTime) (- time) double (/ 1000000000.0 60.0)))

(defn period-task
  []
  (try
    (let [to-retry (->> @state
                     :tasks
                     (filter #(> (-minutes-ago (val %))
                                 (conf :server :retry-timeout-minutes))))]
      (when (seq to-retry)
        (->> to-retry (map val) (map :task) (map dq/retry!) dorun)
        (swap! state -swap-retries to-retry))
      (timbre/info :to-rety to-retry))
    (catch Throwable ex
      (timbre/error ex "error in period task"))))

(defn main
  [port & extra-conf-paths]
  (apply confs/reset! (concat extra-conf-paths ["resources/config.edn"]))
  (def queue (lib/open-queue))
  (def state (atom {:retries {}
                    :tasks {}}))
  (timbre/info confs/*conf*)
  (let [cancel-period-task (time/every (conf :server :period-millis) period-task)
        server (http/start!
                [
                 ;; health checks
                 (GET  "/status"   [] get-status)
                 (POST "/status"   [] post-status)

                 ;; queue lifecycle
                 (POST "/put"      [] post-put)
                 (POST "/take"     [] post-take)
                 (POST "/retry"    [] post-retry)
                 (POST "/complete" [] post-complete)

                 ;; stats
                 (GET "/stats" [] get-stats)

                 (route/not-found "No such page.")]
                port)]
    (fn []
      (cancel-period-task)
      (.close server))))

(defn -main
  [port & extra-conf-paths]
  (lib/setup-logging)
  (apply main (read-string port) extra-conf-paths))
