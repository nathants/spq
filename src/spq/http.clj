(ns spq.http
  (:require [aleph.http :as http]
            [byte-streams :as bs]
            [clojure.core.async :as a]
            [clojure.string :as str]
            [compojure
             [core :as compojure]
             [response :as response]]
            [manifold
             [deferred :as d]
             [stream :as s]]
            [ring.middleware
             [keyword-params :as keyword-params]
             [params :as params]]
            [taoensso.timbre :as timbre]))

(extend-protocol response/Renderable
  manifold.deferred.Deferred
  (render [d _] d))

(defmacro defhandler
  [-name args & forms]
  `(defn ~-name
     ~args
     (let [start# (System/nanoTime)]
       (-> (a/go
             (try (let [~args (map (fn [x#] (update-in x# [:body] #(if % (bs/to-string %) %))) ~args)]
                    ~@forms)
                  (catch Throwable ex#
                    (println ex#)
                    (throw ex#))))
         s/->source
         s/take!
         (d/on-realized
          (fn [rep#] (let [[req#] ~args]
                       (timbre/info (:status rep#)
                                    (str/upper-case (name (:request-method req#)))
                                    (str (:uri req#) "?" (:query-string req#))
                                    (format "%.2f%s" (/ (double (- (System/nanoTime) start#)) 1000000.0) "ms")
                                    (:remote-addr req#))))
          (fn [rep#] (timbre/error "handler" ~name "failed with" ~args rep#)))))))

(defn start!
  [router port]
  (http/start-server
   (-> (apply compojure/routes router)
     keyword-params/wrap-keyword-params
     params/wrap-params)
   {:port port}))
