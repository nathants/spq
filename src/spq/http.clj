(ns spq.http
  (:require [aleph.http :as http]
            [clojure.core.async :as a]
            [compojure
             [core :as compojure]
             [response :as response]]
            [manifold.stream :as s]
            [ring.middleware.params :as params]
            [taoensso.timbre :as timbre]
            [manifold.deferred :as d]
            [byte-streams :as bs]))

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
           (let [~args (map #(update-in % [:body] (fn [x#]
                                                    (if x#
                                                      (bs/to-string x#)
                                                      x#)))
                            ~args)]
             ~@forms)
           (catch Throwable ex#
             (timbre/error ex#)
             (throw ex#))))))))

(defn start!
  [router port]
  (http/start-server
   (->> router (apply compojure/routes) params/wrap-params) {:port port}))

(defn get
  [url & [opts]]
  (d/chain
   (http/get url opts)
   #(update-in % [:body] bs/to-string)))

(defn post
  [url & [opts]]
  (d/chain
   (http/post url opts)
   #(update-in % [:body] bs/to-string)))
