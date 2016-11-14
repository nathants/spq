(ns spq.server-test
  (:require [clj-http.client :as http]
            [clojure.test :refer :all]
            [spq
             [lib :as lib]
             [server :as sut]]))

(defn rand-port
  []
  (+ 1000 (rand-int 10000)))

(defn route
  [port url]
  (format "http://localhost:%s%s" port url))

(defmacro with-server
  [route-fn & forms]
  `(do
     (lib/run "rm -rf" lib/queue-path)
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
                :query-params {:thingy "123"}}
          resp (http/get (url "/status") opts)]
      (is (= opts (lib/json-loads (:body resp)))))))

(deftest post-status
  (with-server url
    (let [opts {:body "a string"
                :headers {:status "+1"}
                :query-params {:thingy "123"}}
          resp (http/post (url "/status") opts)]
      (is (= opts (lib/json-loads (:body resp)))))))

(deftest kitchen-sink
  (with-server url
    (let [item {:work-item "number1"}

          ;; put an item on a queue
          resp (http/post (url "/put") {:body (lib/json-dumps item)
                                        :query-params {:queue "queue_1"}})
          _ (is (= 200 (:status resp)))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:enqueued 1 :retried 0 :completed 0 :in-progress 0}}
                   (lib/json-loads (:body resp))))

          ;; take an item off the queue
          resp (http/post (url "/take?queue=queue_1"))
          id (-> resp :headers :id)
          _ (is (= 200 (:status resp)))
          _ (is (= (count id) (count (lib/uuid))))
          _ (is (= item (lib/json-loads (:body resp))))

          ;; take fails when there is nothing to take
          resp (http/post (url "/take?queue=queue_1") {:query-params {:timeout-ms 1}})
          _ (is (= 204 (:status resp)))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:enqueued 1 :retried 0 :completed 0 :in-progress 1}}
                   (lib/json-loads (:body resp))))

          ;; retry the item, aka re-enqueue it
          resp (http/post (url "/retry") {:body id})
          _ (is (= 200 (:status resp)))

          ;; retry is idempotent
          resp (http/post (url "/retry") {:body id})
          _ (is (= 200 (:status resp)))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:enqueued 1 :retried 1 :completed 0 :in-progress 0}}
                   (lib/json-loads (:body resp))))

          ;; take the retried item
          resp (http/post (url "/take?queue=queue_1"))
          id (-> resp :headers :id)
          _ (is (= 200 (:status resp)))
          _ (is (= (count id) (count (lib/uuid))))
          _ (is (= item (lib/json-loads (:body resp))))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:enqueued 1 :retried 1 :completed 0 :in-progress 1}}
                   (lib/json-loads (:body resp))))

          ;; complete the item, marking it as done
          resp (http/post (url "/complete") {:body id})
          _ (is (= 200 (:status resp)))


          ;; complete is idempotent
          resp (http/post (url "/complete") {:body id})
          _ (is (= 200 (:status resp)))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:enqueued 1 :retried 1 :completed 1 :in-progress 0}}
                   (lib/json-loads (:body resp))))

          ;; take fails when there is nothing to take
          resp (http/post (url "/take?queue=queue_1") {:query-params {:timeout-ms 1}})
          _ (is (= 204 (:status resp)))])))

;; TODO test auto timeout
