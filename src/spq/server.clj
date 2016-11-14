(ns spq.server
  (:gen-class)
  (:require [byte-streams :as bs]
            [durable-queue :as dq]
            [manifold.deferred :as d]
            [clojure.core.async :as a]
            [spq.http :as http :refer [defhandler]]
            [spq.lib :as lib]
            [compojure
             [core :as compojure :refer [GET POST]]
             [route :as route]]
            [taoensso.timbre :as timbre]
            [cheshire.core :as json]))

(def queue)
(def conf)
(def tasks)

(defn new-task-id
  [] ;; putIfAbsent to the atom. maybe just use j.u.ConcHashMap?
  (let [id (lib/uuid)
        m (swap! tasks #(if-not (get % id)
                          (assoc % id nil)
                          %))]
    (if (contains? m id)
      id
      (recur))))

(defhandler get-status
  [req]
  {:status 200
   :body (lib/json-dumps
          {:headers {:status (:status (:headers req))}
           :params (:params req)})})

(defhandler post-status
  [req]
  {:status 200
   :body (lib/json-dumps
          {:body (:body req)
           :headers {:status (:status (:headers req))}
           :params (:params req)})})

(defhandler post-take
  [req]
  (let [queue-name (:queue (:params req))
        task (dq/take! queue queue-name 5000 ::empty)]
    (if (= ::empty task)
      {:status 204
       :body "not items available to take"}
      (let [id (new-task-id)
            ;; TODO need to wrap task derefing in a try catch because
            ;; it can io error on checksum fail
            item @task]
        (swap! tasks assoc id task) ;; TODO how do deal with auto
                                    ;; retry? need some kind of
                                    ;; timeout
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
  (timbre/info queue)
  {:status 200
   :body (->> queue
           dq/stats
           (reduce-kv (fn [m k v]
                        (assoc m k (select-keys v [:enqueued :retried :completed :in-progress])))
                      {})
           lib/json-dumps)})

(defn main
  [port & confs]
  (def conf (apply lib/load-confs confs))
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
    #_(POST "/retry"    [] post-retry)
    #_(POST "/complete" [] post-complete)

    ;; stats
    (GET "/stats" [] get-stats)

    (route/not-found "No such page.")]
   port))

(defn -main
  [port & confs]
  (apply main (read-string port) confs))
