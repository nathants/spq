(ns spq.server-test
  (:require [spq.server :as sut]
            [spq.http :as http]
            [clojure.test :refer :all]
            [manifold.deferred :as d]
            [taoensso.timbre :as timbre]
            [byte-streams :as bs]))

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
    (let [opts {:headers {:status "+1"}}]
      (is (= (str "good to go. thanks for: " opts)
             (:body @(http/get (route "/status" port) opts)))))))

(deftest post-status
  (with-server-on port
    (let [opts {:body "blah"
                :headers {:status "+1"}}]
      (is (= (str "good to go. thanks for: " opts)
             (:body @(http/post (route "/status" port) opts)))))))
