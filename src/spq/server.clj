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
      {:status 204})))

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
      {:status 204})))

(defn -swap-take!
  [state task retry-timeout-minutes]
  (loop [id (or (get-in @state [:retries task])
                (str (hash task)))
         i 0]
    (condp = (try
               (swap! state (fn [m]
                              (assert (not (contains? m id)))
                              (assoc-in m [:tasks id] {:task task
                                                       :nano-time (System/nanoTime)
                                                       :retry-timeout-minutes retry-timeout-minutes})))
               (catch AssertionError ex
                 (timbre/debug "task-id collission, looping. count:" i id)
                 (if (> i 1000)
                   (timbre/fatal "failed to find unique id for task, this should never happen")
                   (lib/shutdown))
                 ::fail))
      ::fail (recur (str (hash (Object.))) (inc i))
      id)))

(defhandler post-take
  [req]
  (let [queue-name (:queue (:params req))
        timeout-millis (Long/parseLong (get (:params req) :timeout-millis (str (conf :server :take-timeout-millis))))
        retry-timeout-minutes (Double/parseDouble (get (:params req) :retry-timeout-minutes (str (conf :server :retry-timeout-minutes))))
        task (dq/take! queue queue-name timeout-millis ::empty)]
    (if (= ::empty task)
      (do (timbre/debug "take! nothing to take for queue:" queue-name)
          {:status 204})
      (let [item (lib/deref-task task)
            id (-swap-take! state task retry-timeout-minutes)]
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

(defn periodic-task
  [stop-periodic-task]
  (when-not @stop-periodic-task
    (try
      (let [to-retry (->> @state
                       :tasks
                       (filter #(> (lib/minutes-ago (:nano-time (val %)))
                                   (get (val %) :retry-timeout-minutes))))]
        (when (seq to-retry)
          (timbre/info "period task found" (count to-retry) "tasks to retry")
          (->> to-retry (map val) (map :task) (map dq/retry!) dorun)
          (swap! state #(reduce (fn [% [id {:keys [task]}]]
                                  (-swap-retry % id task))
                                %
                                to-retry))))
      (time/in (conf :server :period-millis) #(periodic-task stop-periodic-task))
      (catch Throwable ex
        (timbre/fatal ex "error in period task")
        (lib/shutdown)))))

(defn main
  [port & {:keys [extra-handlers
                  extra-middleware]}]
  (def queue (lib/open-queue))
  (def state (atom {:retries {}
                    :tasks {}}))
  ;; TODO we should probably monitor the periodic task via a binary
  ;; sibling process, health checking each other, either kills parent
  ;; pid of fail to check in.
  (let [stop-periodic-task (atom false)
        server (-> [
                    ;; health checks
                    (GET  "/status"   [] get-status)
                    (POST "/status"   [] post-status)

                    ;; queue lifecycle
                    (POST "/put"      [] post-put)
                    (POST "/take"     [] post-take)
                    (POST "/retry"    [] post-retry)
                    (POST "/complete" [] post-complete)

                    ;; queue stats
                    (GET "/stats"     [] get-stats)]

                 (concat extra-handlers [(route/not-found "No such page.")])
                 (http/start! :port port :extra-middleware extra-middleware))]
    (time/in (conf :server :period-millis) #(periodic-task stop-periodic-task))
    (fn close-fn []
      (reset! stop-periodic-task true)
      (.close server))))

(defn -main
  [port & confs]
  (lib/setup-logging)
  (apply confs/reset! confs)
  (main (read-string port)))
