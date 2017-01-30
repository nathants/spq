(ns spq.server-test
  (:require [clj-http.client :as http]
            [clojure.test :refer :all]
            [confs.core :as confs :refer [conf]]
            [compojure
             [core :refer [GET POST]]
             [route :as route]]
            [spq
             [lib :as lib]
             [http :refer [defhandler defmiddleware]]
             [server :as sut]]
            [taoensso.timbre :as timbre]
            [byte-streams :as bs]
            [manifold.deferred :as d]))

(defn rand-port
  []
  (+ 1000 (rand-int 5000)))

(defn route
  [port url]
  (format "http://localhost:%s%s" @port url))

(defmacro with-server
  [route-fn reboot-server-fn opts & forms]
  `(do
     (lib/run "rm -rf" lib/queue-path) ;; TODO use a tempdir here instead of lib/queue-path
     (lib/setup-logging :short-format true)
     (apply confs/reset! (concat (:confs ~opts) ["resources/config.edn"]))
     (let [
           port# (atom nil)
           close-fn# (atom nil)
           ~route-fn #(route port# %)
           start-fn# #(do (loop [i# 0]
                            (assert (< i# 50))
                            (when (= ::fail (try
                                              (reset! port# (rand-port))
                                              (reset! close-fn# (apply sut/main @port# (apply concat ~opts)))
                                              (catch java.net.SocketException _# ::fail)
                                              (catch java.net.BindException   _# ::fail)))
                              (recur (inc i#))))
                          (loop [j# 0]
                            (assert (< j# 50))
                            (when (= ::fail (try
                                              (http/get (~route-fn "/status"))
                                              (catch Throwable _# ::fail)))
                              (recur (inc j#)))))
           ~reboot-server-fn (fn []
                               (@close-fn#)
                               (start-fn#))]
       (start-fn#)
       (try
         ~@forms
         (finally
           (@close-fn#))))))

(deftest get-status
  (with-server url _ {}
    (let [opts {:headers {:status "+1"}
                :query-params {:thingy "123"}}
          resp (http/get (url "/status") opts)]
      (is (= opts (lib/json-loads (:body resp)))))))

(deftest post-status
  (with-server url _ {}
    (let [opts {:body "a string"
                :headers {:status "+1"}
                :query-params {:thingy "123"}}
          resp (http/post (url "/status") opts)]
      (is (= opts (lib/json-loads (:body resp)))))))

(deftest kitchen-sink
  (with-server url _ {}
    (let [item {:work-num "number1"}

          ;; TODO stop hard coding queue_1

          ;; put an item on a queue
          resp (http/post (url "/put") {:body (lib/json-dumps item)
                                        :query-params {:queue "queue_1"}})
          _ (is (= 200 (:status resp)))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 1 :num-active 0}}
                   (lib/json-loads (:body resp))))

          ;; take an item off the queue
          resp (http/post (url "/take?queue=queue_1"))
          id (-> resp :headers :id)
          _ (is (string? id))
          _ (is (= 200 (:status resp)))
          _ (is (= item (lib/json-loads (:body resp))))

          ;; take fails when there is nothing to take
          resp (http/post (url "/take?queue=queue_1&timeout-millis=10"))
          _ (is (= 204 (:status resp)))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 1 :num-active 1}}
                   (lib/json-loads (:body resp))))

          ;; retry the item, aka re-enqueue it
          resp (http/post (url "/retry") {:body id})
          _ (is (= 200 (:status resp)))

          ;; retry is idempotent, but returns 204 when nothing to retry
          resp (http/post (url "/retry") {:body id})
          _ (is (= 204 (:status resp)))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 1 :num-active 0}}
                   (lib/json-loads (:body resp))))

          ;; take the retried item
          resp (http/post (url "/take?queue=queue_1"))
          id (-> resp :headers :id)
          _ (is (= 200 (:status resp)))
          _ (is (= item (lib/json-loads (:body resp))))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 1 :num-active 1}}
                   (lib/json-loads (:body resp))))

          ;; complete the item, marking it as done
          resp (http/post (url "/complete") {:body id})
          _ (is (= 200 (:status resp)))

          ;; complete 204s if that id is not completeable
          resp (http/post (url "/complete") {:body id})
          _ (is (= 204 (:status resp)))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 0 :num-active 0}}
                   (lib/json-loads (:body resp))))

          ;; take fails when there is nothing to take
          resp (http/post (url "/take?queue=queue_1&timeout-millis=10"))
          _ (is (= 204 (:status resp)))]

      ;; no garbage left in state
      (is (= {} @sut/state)))))

(deftest add-a-few-items
  (with-server url _ {}
    (let [items (for [i (range 10)]
                  {:work-num i})

          few-i  [0 1 2 3]
          more-i [4 5 6 7]
          rest-i [8 9]

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))

          _ (doseq [item items]
              (let [resp (http/post (url "/put") {:body (lib/json-dumps item)
                                                  :query-params {:queue "queue_1"}})
                    _ (is (= 200 (:status resp)))]))


          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 10 :num-active 0}}
                   (lib/json-loads (:body resp))))

          ;; take the few
          the-few (vec
                   (for [i few-i]
                     (let [resp (http/post (url "/take?queue=queue_1"))
                           _ (is (= 200 (:status resp)))
                           id (-> resp :headers :id)
                           item (lib/json-loads (:body resp))]
                       (is (= item (nth items i)))
                       {:id id :item item})))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 10 :num-active 4}}
                   (lib/json-loads (:body resp))))

          ;; take the more
          the-more (vec
                    (for [i more-i]
                      (let [resp (http/post (url "/take?queue=queue_1"))
                            _ (is (= 200 (:status resp)))
                            id (-> resp :headers :id)
                            item (lib/json-loads (:body resp))]
                        (is (= item (nth items i)))
                        {:id id :item item})))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 10 :num-active 8}}
                   (lib/json-loads (:body resp))))


          ;; retry the the-few
          _ (doseq [{:keys [id]} the-few]
              (let [resp (http/post (url "/retry") {:body id})
                    _ (is (= 200 (:status resp)))]))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 10 :num-active 4}}
                   (lib/json-loads (:body resp))))

          ;; complete the the-more
          _ (doseq [{:keys [id]} the-more]
              (let [resp (http/post (url "/complete") {:body id})
                    _ (is (= 200 (:status resp)))]))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 6 :num-active 0}}
                   (lib/json-loads (:body resp))))

          ;; take the rest, which should be the few which were all retried instead of completed
          the-rest (vec
                    (for [i rest-i]
                      (let [resp (http/post (url "/take?queue=queue_1"))
                            _ (is (= 200 (:status resp)))
                            id (-> resp :headers :id)
                            item (lib/json-loads (:body resp))]
                        (is (= item (nth items i)))
                        {:id id :item item})))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 6 :num-active 2}}
                   (lib/json-loads (:body resp))))

          ;; complete the rest
          _ (doseq [{:keys [id]} the-rest]
              (let [resp (http/post (url "/complete") {:body id})
                    _ (is (= 200 (:status resp)))]))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 4 :num-active 0}}
                   (lib/json-loads (:body resp))))

          everything-else (loop [res []]
                            (let [resp (http/post (url "/take?queue=queue_1&timeout-millis=50"))]
                              (condp = (:status resp)
                                200 (recur (conj res {:id (-> resp :headers :id)
                                                      :item (lib/json-loads (:body resp))}))
                                204 res)))

          _ (is (= (map :item everything-else)
                   (map :item the-few)))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 4 :num-active 4}}
                   (lib/json-loads (:body resp))))

          ;; complete everything else
          _ (doseq [{:keys [id]} everything-else]
              (let [resp (http/post (url "/complete") {:body id})
                    _ (is (= 200 (:status resp)))]))

          ;; check stats
          resp (http/get (url "/stats"))
          _ (is (= 200 (:status resp)))
          _ (is (= {:queue_1 {:num-queued 0 :num-active 0}}
                   (lib/json-loads (:body resp))))

          ;; the retries of the-few put them back at the end of the queue
          _ (is (= (concat (drop 4 items) (take 4 items))
                   (map :item (concat the-more the-rest everything-else))))

          ;; what came in is what came out
          _ (is (= items
                   (sort-by :work-num (map :item (concat the-more the-rest everything-else)))
                   (sort-by :work-num (map :item (concat the-few the-more the-rest)))))]

      ;; no garbage left in state
      (is (= {} @sut/state)))))

;; TODO implement retries with aleph.time/in or aleph.time/every

(deftest auto-retry-timeout-via-conf
  (with-server url _ {:confs [(pr-str {:server {:period-millis 50
                                                :retry-timeout-minutes 0.001}})]}
    (let [item {:work-num "number1"}

          ;; put an item on a queue
          resp (http/post (url "/put") {:body (lib/json-dumps item)
                                        :query-params {:queue "queue_1"}})
          _ (is (= 200 (:status resp)))

          ;; take an item off the queue
          resp (http/post (url "/take?queue=queue_1"))
          _ (is (= item (lib/json-loads (:body resp))))

          ;; take fails, there is nothing in the queue
          resp (http/post (url "/take?queue=queue_1&timeout-millis=10"))
          _ (is (= 204 (:status resp)))

          ;; sleep so it gets auto retried, and re-enqueued
          _ (Thread/sleep 100)

          ;; take the same item, without every calling /retry
          resp (http/post (url "/take?queue=queue_1"))
          _ (is (= item (lib/json-loads (:body resp))))

          resp (http/post (url "/complete") {:body (-> resp :headers :id)})
          _ (is (= 200 (:status resp)))]

      ;; no garbage left in state
      (is (= {} @sut/state)))))

(deftest auto-retry-timeout-via-param
  (with-server url _ {:confs [(pr-str {:server {:period-millis 50}})]}
    (let [item {:work-num "number1"}

          ;; put an item on a queue
          resp (http/post (url "/put") {:body (lib/json-dumps item)
                                        :query-params {:queue "queue_1"}})
          _ (is (= 200 (:status resp)))

          ;; take an item off the queue
          resp (http/post (url "/take?queue=queue_1&retry-timeout-minutes=0.001"))
          _ (is (= item (lib/json-loads (:body resp))))

          ;; take fails, there is nothing in the queue
          resp (http/post (url "/take?queue=queue_1&timeout-millis=10"))
          _ (is (= 204 (:status resp)))

          ;; sleep so it gets auto retried, and re-enqueued
          _ (Thread/sleep 100)

          ;; take the same item, without every calling /retry
          resp (http/post (url "/take?queue=queue_1"))
          _ (is (= item (lib/json-loads (:body resp))))

          resp (http/post (url "/complete") {:body (-> resp :headers :id)})
          _ (is (= 200 (:status resp)))]

      ;; no garbage left in state
      (is (= {} @sut/state)))))

(deftest extra-handlers
  (let [extra-handlers [(GET "/foo" [] (defhandler foo
                                         [req]
                                         {:status 200
                                          :body "bar"}))]]
    (with-server url _ {:extra-handlers extra-handlers}
      (let [resp (http/get (url "/foo"))]
        (is (= 200 (:status resp)))
        (is (= "bar" (:body resp)))))))

(deftest extra-middleware
  (let [extra-handlers [(GET "/foo" [] (defhandler foo
                                         [req]
                                         {:status 200
                                          :body (-> req :headers :algo)}))]
        extra-middleware [(defmiddleware middle-out
                            ([req] (assoc-in req [:headers :algo] "middle"))
                            ([req rep] (assoc-in rep [:headers :algo] "out")))]]
    (with-server url _ {:extra-handlers extra-handlers
                        :extra-middleware extra-middleware}
      (let [resp (http/get (url "/foo"))]
        (is (= 200 (:status resp)))
        (is (= "middle" (:body resp)))
        (is (= "out" (-> resp :headers :algo)))))))

(deftest extra-middleware-short-circuit-request
  (let [extra-middleware [(defmiddleware middle-out
                            ([req] {:status 200 :body "sorry bud"})
                            ([req rep] rep))]]
    (with-server url _ {:extra-middleware extra-middleware}
      (let [resp (http/get (url "/foo"))]
        (is (= "sorry bud" (:body resp)))))))

;; TODO add tests for accessing queues that dont exist. seems like all good?
