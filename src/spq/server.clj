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

(defhandler status-handler
  [req]
  {:status 200 :body "good to go"})

(defn main
  [port]
  (http/start!
   [(GET  "/status" [] status-handler)
    (route/not-found "No such page.")]
   port))

(defn -main
  [port]
  (main port))
