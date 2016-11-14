(ns spq.server-test
  (:require [spq.server :as sut]
            [spq.http :as http]
            [clojure.test :refer :all]
            [manifold.deferred :as d]
            [taoensso.timbre :as timbre]
            [byte-streams :as bs]
            [spq.lib :as lib]
            [durable-queue :as dq]))

(defn rand-port
  []
  (+ 1000 (rand-int 10000)))

(defn route
  [port url]
  (format "http://localhost:%s%s" port url))

(defmacro with-server
  [route-fn & forms]
  `(do
     (try
       (dq/delete! sut/queue)
       (catch Throwable ex#
         nil))
     (let [port# (rand-port)
           server# (sut/main port#)
           ~route-fn #(route port# %)]
       (try
         ~@forms
         (finally
           (.close server#))))))


(deftest get-status
  (with-server url
    (let [opts {:headers {:status "+1"}
                :params {:thingy "123"}}
          resp @(http/get #spy/p (url "/status") opts)]
      (is (= opts (lib/json-loads (:body resp)))))))

(deftest post-status
  (with-server url
    (let [opts {:body "a string"
                :headers {:status "+1"}
                :params {:thingy "123"}}
          resp @(http/post (url "/status") opts)]
      (is (= opts (lib/json-loads (:body resp)))))))

(deftest kitchen-sink
  (with-server url
    (let [item {:work-item "number1"}
          ;; put!
          opts {:body (lib/json-dumps item)
                :params {:queue "queue_1"}}
          resp @(http/post (url "/put") opts)
          _ (is (= 200 (:status resp)))
          ;; stats
          resp @(http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:enqueued 1
                              :retried 0
                              :completed 0
                              :in-progress 0}}
                   (lib/json-loads (:body resp))))
          ;; take!
          resp @(http/post (url "/take?queue=queue_1"))
          _ (is (= 200 (:status resp)))
          _ (is (-> resp :headers :id))
          _ (is (= item (lib/json-loads (:body resp))))
          ;; stats
          resp @(http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:enqueued 1
                              :retried 0
                              :completed 0
                              :in-progress 1}}
                   (lib/json-loads (:body resp))))
          ])))
