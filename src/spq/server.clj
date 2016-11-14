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
            [taoensso.timbre :as timbre]))

(def queue)
(def conf)

(defhandler get-status
  [req]
  {:status 200
   :body (lib/json-dumps
          {:headers {:status (:status (:headers req))}
           :query-params (:query-params req)})})

(defhandler post-status
  [req]
  {:status 200
   :body (lib/json-dumps
          {:body (:body req)
           :headers {:status (:status (:headers req))}
           :query-params (:query-params req)})})

(defhandler post-put
  [req]
  )

(defn main
  [port & confs]
  ;; (def conf (apply lib/load-confs confs))
  ;; (def queue (lib/open-queue))
  (http/start!
   [
    ;; health checks
    (GET  "/status"   [] get-status)
    (POST "/status"   [] post-status)

    ;; queue lifecycle
    ;; (POST "/put"      [] post-put)
    #_(POST "/take"     [] post-take)
    #_(POST "/retry"    [] post-retry)
    #_(POST "/complete" [] post-complete)

    ;; stats
    #_(GET "/stats" [] get-stats)

    (route/not-found "No such page.")]
   port))

(defn -main
  [port & confs]
  (apply main (read-string port) confs))
