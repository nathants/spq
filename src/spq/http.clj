(ns spq.http
  (:require aleph.http
            [clojure.core.async :as a]
            [compojure
             [core :as compojure]
             [response :as response]]
            [manifold.stream :as s]
            [ring.middleware.params :as params]
            [taoensso.timbre :as timbre]))

(extend-protocol response/Renderable
  manifold.deferred.Deferred
  (render [d _] d))

(defmacro defhandler
  [name args & forms]
  `(defn ~name
     ~args
     (s/take!
      (s/->source
       (a/go
         (try
           ~@forms
           (catch Throwable ex#
             (timbre/error ex#)
             (throw ex#))))))))

(defn start!
  [router port]
  (aleph.http/start-server
   (->> router (apply compojure/routes) params/wrap-params)
   {:port port}))
