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
     (let [start# (System/nanoTime)]
       (d/on-realized
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
                (println ex#)
                (throw ex#))))))
        (fn [rep#] (let [[req#] ~args]
                     (timbre/info (:status rep#)
                                  (:request-method req#)
                                  (:uri req#)
                                  (format "%.2f%s" (/ (double (- (System/nanoTime) start#)) 1000000.0) "ms"))))
        (fn [rep#] (println "error with" ~args rep#))))
     ))

(defn start!
  [router port]
  (http/start-server
   (params/wrap-params (apply compojure/routes router))
   {:port port}))

(defn get
  [url & [opts]]
  (-> (http/get url opts)
    (d/chain #(update-in % [:body] bs/to-string))
    (d/timeout! 1000 :fail)))

(defn post
  [url & [opts]]
  (-> (http/post url opts)
    (d/chain #(update-in % [:body] bs/to-string))
    (d/timeout! 1000 :fail)))
