(ns spq.server
  (:gen-class)
  (:require [compojure
             [core :as compojure :refer [GET POST]]
             [route :as route]]
            [confs.core :as confs :refer [conf]]
            [durable-queue :as dq]
            [spq
             [http :as http :refer [defhandler]]
             [lib :as lib]]
            [taoensso.timbre :as timbre]))

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

(defhandler post-retry
  [req]
  (let [id (:body req)]
    (if-let [task (get-in @state [:tasks id :task])]
      (do (dq/retry! task)
          (swap! state assoc-in [:retries task] id)
          (timbre/info "retry!" id (lib/abbreviate (lib/deref-task task)))
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
          (swap! state (fn [m]
                         (-> m
                           (update-in [:retries] dissoc task)
                           (update-in [:tasks] dissoc id))))
          (timbre/info "complete!" id (lib/abbreviate (lib/deref-task task)))
          {:status 200
           :body (str "completed for task with id: " id)})
      {:status 204
       :body (str "no such task to complete with id: " id
                  ". either it has already been completed, "
                  "or the server crashed and it will be "
                  "taken again by another caller.")})))

(defn assoc-task
  [m id task]
  (assert (not (contains? m id)))
  (assoc-in m [:tasks id] {:task task
                           :time (System/nanoTime)}))

(defn assoc-task!
  [state task]
  (loop [id (or (get-in @state [:retries task])
                (str (hash task)))
         i 0]
    (condp = (try
               (swap! state assoc-task id task)
               (catch AssertionError ex
                 (timbre/info "task-id collission, looping. count:" i id)
                 (if (> i 1000)
                   (timbre/error "failed to find unique id for task, this should never happen")
                   (lib/shutdown))
                 ::fail))
      ::fail (recur (str (hash (Object.))) (inc i))
      id)))

(defhandler post-take
  [req]
  (let [queue-name (:queue (:params req))
        default (str (conf :server :take-timeout-milliseconds))
        timeout (Long/parseLong (get (:params req) :timeout-ms default))
        task (dq/take! queue queue-name timeout ::empty)]
    (if (= ::empty task)
      (do (timbre/info "nothing to take for queue:" queue-name)
          {:status 204
           :body "no items available to take"})
      (let [item (lib/deref-task task)
            id (assoc-task! state task)]
        (timbre/info "take!" queue-name item)
        {:status 200
         :headers {:id id}
         :body (lib/json-dumps item)}))))

(defhandler post-put
  [req]
  (let [queue-name (:queue (:params req))
        item (lib/json-loads (:body req))]
    (dq/put! queue queue-name item)
    (timbre/info "put!" queue-name item)
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

(defn main
  [port & extra-conf-paths]
  (apply confs/reset! (concat extra-conf-paths ["resources/config.edn"]))
  (def queue (lib/open-queue))
  (def state (atom {:retries {}
                    :tasks {}}))
  (http/start!
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
   port))

(defn -main
  [port & extra-conf-paths]
  (apply main (read-string port) extra-conf-paths))
