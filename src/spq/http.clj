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

(defn -body-to-string
  [x]
  (->> (if-let [body (:body x)]
         (bs/to-string body))
    (assoc x :body)))

(defmacro defhandler
  [-name args & forms]
  `(defn ~-name
     ~args
     (let [start# (System/nanoTime)]
       (-> (let [~args [(-body-to-string (first ~args))]]
             ~@forms)
         (try (catch Throwable ex#
                (timbre/error ex# "handler" '~-name "failed with" (first ~args))
                (throw ex#)))
         a/go
         s/->source
         s/take!
         (d/on-realized
          (fn [rep#] (let [[req#] ~args]
                       (when (:remote-addr req#)
                         (timbre/info (or (:status rep#) 500)
                                      (str/upper-case (name (:request-method req#)))
                                      (str (:uri req#) "?" (:query-string req#))
                                      (format "%.2f%s" (/ (double (- (System/nanoTime) start#)) 1000000.0) "ms")
                                      (:remote-addr req#)))))
          (fn [rep#] (timbre/error "deferred" '~-name "failed with" (first ~args) rep#)))))))

(defmacro defmiddleware
  [name request-form response-form]
  `(let [request-fn# (fn ~request-form)
         response-fn# (fn ~response-form)]
     (defn ~name
       [handler#]
       (fn [request#]
         (let [processed-request# (request-fn# request#)]
           ;; if :status is defined, assume this is a response and short circuit the normal handler
           (if (:status processed-request#)
             processed-request#
             (d/chain (handler# processed-request#) #(response-fn# request# %))))))))

(defn start!
  [router & {:keys [port extra-middleware]}]
  (http/start-server
   (reduce #(%2 %1)
           (apply compojure/routes router)
           (concat extra-middleware
                   [keyword-params/wrap-keyword-params
                    params/wrap-params]))
   {:port port}))
