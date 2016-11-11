(ns spq.server
  (:gen-class)
  (:require [byte-streams :as bs]
            [cheshire.core :as json]
            [manifold.deferred :as d]
            [clojure.core.async :as a]
            [spq.http :as http :refer [defhandler]]
            [compojure
             [core :as compojure :refer [GET POST]]
             [route :as route]]
            [taoensso.timbre :as timbre]))

(defhandler get-status-handler
  [req]
  {:status 200 :body (str "good to go. thanks for: "  {:headers {:status (:status (:headers req))}})})

(defhandler post-status-handler
  [req]
  {:status 200 :body (str "good to go. thanks for: " {:body (:body req)
                                                      :headers {:status (:status (:headers req))}})})

(defn main
  [port]
  (http/start!
   [(GET  "/status" [] get-status-handler)
    (POST  "/status" [] post-status-handler)
    (route/not-found "No such page.")]
   port))

(defn -main
  [port]
  (main port))
