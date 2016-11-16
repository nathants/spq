(ns spq.server
  (:gen-class)
  (:require [compojure
             [core :as compojure :refer [GET POST]]
             [route :as route]]
            [clj-confs.core :as confs]
            [durable-queue :as dq]
            [spq
             [http :as http :refer [defhandler]]
             [lib :as lib]]
            [taoensso.timbre :as timbre]))

(def queue)
(def conf)
(def tasks)

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
    (if-let [task (get-in @tasks [id :task])]
      (do (dq/retry! task)
          ;; (swap! tasks assoc-in [:retries])
          (timbre/info "retry!" id (lib/abbreviate (lib/deref-task task)))
          {:status 200
           :body (str "marked retry for task with id: " id)})
      {:status 204
       :body (str "no such task to retry with id: " id)})))


(defhandler post-complete
  [req]
  (let [id (:body req)]
    (if-let [task (get-in @tasks [id :task])]
      (do (dq/complete! task)
          (timbre/info "complete!" id (lib/abbreviate (lib/deref-task task)))
          {:status 200
           :body (str "completed for task with id: " id)})
      {:status 204
       :body (str "no such task to complete with id: " id)})))

(defn assoc-task
  [m id task]
  (assert (not (contains? m id)))
  (assoc m id {:task task
               :time (System/nanoTime)}))

(defn assoc-task!
  [tasks task]
  (loop [id (str (hash task))
         i 0]
    (condp = (try
               (swap! tasks assoc-task id task)
               (catch AssertionError ex
                 (timbre/info "task-id collission, looping. count:" i id @tasks)
                 (if (> i 1000)
                   (timbre/error "failed to find tasks unique id for tasks task, this should never happen")
                   (lib/shutdown))
                 ::fail))
      ::fail (recur (str (hash (Object.))) (inc i))
      id)))

(defhandler post-take
  [req]
  (let [queue-name (:queue (:params req))
        timeout-ms (Long/parseLong (get (:params req) :timeout-ms "5000"))
        task (dq/take! queue queue-name timeout-ms ::empty)]
    (if (= ::empty task)
      (do (timbre/info "nothing to take for queue:" queue-name)
          {:status 204
           :body "no items available to take"})
      (let [item (lib/deref-task task)
            id (assoc-task! tasks task)]
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
           (reduce-kv #(assoc %1 %2 (select-keys %3 [:enqueued :retried :completed :in-progress])) {})
           lib/json-dumps)})

(defn main
  [port & conf-paths]
  (def conf (apply confs/load conf-paths))
  (def queue (lib/open-queue))
  (def tasks (atom {}))
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
  [port & conf-paths]
  (apply main (read-string port) conf-paths))
