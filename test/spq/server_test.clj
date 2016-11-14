(ns spq.server-test
  (:require [spq.server :as sut]
            [spq.http :as http]
            [clojure.test :refer :all]
            [manifold.deferred :as d]
            [taoensso.timbre :as timbre]
            [byte-streams :as bs]
            [spq.lib :as lib]))

(defn rand-port
  []
  (+ 1000 (rand-int 10000)))

(defmacro with-server-on
  [port & forms]
  `(let [~port (rand-port)
         server# (sut/main ~port)]
     (try
       ~@forms
       (finally
         (.close server#)))))

(defn route
  [url port]
  (format "http://localhost:%s%s" port url))

(deftest get-status
  (with-server-on port
    (let [opts {:headers {:status "+1"}
                :query-params {:thingy "123"}}]
      (is (= opts
             (-> "/status"
               (route port)
               (http/get opts)
               deref
               :body
               lib/json-loads))))))

(deftest post-status
  (with-server-on port
    (let [opts {:body "a string"
                :headers {:status "+1"}
                :query-params {:thingy "123"}}]
      (is (= opts (-> "/status"
                    (route port)
                    (http/post opts)
                    deref
                    :body
                    lib/json-loads))))))

;; (deftest kitchen-sink
;;   (with-server-on port
;;     (let [opts {:body {:item :task1}
;;                 :url}
;;           resp @(http/post (route "/put" port) opts)])))
