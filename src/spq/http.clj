(ns spq.http
  (:require [aleph.http :as http]
            [clojure.core.async :as a]
            [compojure
             [core :as compojure]
             [response :as response]]
            [clojure.string :as str]
            [manifold.stream :as s]
            [ring.middleware.params :as params]
            [ring.middleware.keyword-params :as keyword-params]
            [taoensso.timbre :as timbre]
            [manifold.deferred :as d]
            [byte-streams :as bs]))

(extend-protocol response/Renderable
  manifold.deferred.Deferred
  (render [d _] d))

(defmacro defhandler
  [-name args & forms]
  `(defn ~-name
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
                                  (str/upper-case (name (:request-method req#)))
                                  (str (:uri req#) "?" (:query-string req#))
                                  (format "%.2f%s" (/ (double (- (System/nanoTime) start#)) 1000000.0) "ms")
                                  (:remote-addr req#))))
        (fn [rep#] (println "error with" ~args rep#))))))

(defn start!
  [router port]
  (http/start-server
   (-> (apply compojure/routes router)
     keyword-params/wrap-keyword-params
     params/wrap-params
     )
   {:port port}))

(defn -update
  [opts]
  (when opts
    (-> opts
      (assoc :query-params (:params opts))
      (dissoc :params))))

(defn get
  [url & [opts]]
  (-> (http/get url (-update opts))
    (d/chain #(update-in % [:body] bs/to-string))
    (d/timeout! 1000 :fail)))

(defn post
  [url & [opts]]
  (-> (http/post url (-update opts))
    (d/chain #(update-in % [:body] bs/to-string))
    (d/timeout! 1000 :fail)))
