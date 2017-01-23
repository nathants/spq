(ns spq.server-props-test
  (:require [clj-http.client :as http]
            [clojure.test :refer :all]
            [clojure.test.check
             [clojure-test :refer [defspec]]
             [properties :as prop]]
            [spq
             [dq-props-test :as dq-test]
             [lib :as lib]
             [server-test :refer [with-server]]]))

(defn actual
  [ops]
  (with-server url _ {}
    (let [queue "test_queue"
          f (fn [acc [op x]]
              (condp = op
                :put! (do (http/post (url "/put") {:body (lib/json-dumps x) :query-params {:queue queue}})
                          acc)
                :take! (let [resp (http/post (url "/take") {:query-params {:queue queue :timeout-millis 0}})
                             id (-> resp :headers :id)]
                         (condp = (:status resp)
                           204 acc
                           200 (-> acc
                                 (assoc-in [:taken x] (lib/json-loads (:body resp)))
                                 (assoc-in [:ids x] id))))
                :complete! (let [resp (http/post (url "/complete") {:body (get-in acc [:ids x]) :query-params {:queue queue}})]
                             (condp = (:status resp)
                               204 acc
                               200 (-> acc
                                     (assoc-in [:completed x] (get-in acc [:taken x]))
                                     (update-in [:taken] dissoc x))))
                :retry! (let [resp (http/post (url "/retry") {:body (get-in acc [:ids x]) :query-params {:queue queue}})]
                          (condp = (:status resp)
                            204 acc
                            200 (update-in acc [:taken] dissoc x)))))]
      (-> (reduce f {:taken {} :completed {} :ids {}} ops)
        (assoc :queued (loop [res []]
                         (let [resp (http/post (url "/take") {:query-params {:queue queue :timeout-millis 0}})]
                           (condp = (:status resp)
                             200 (recur (conj res (lib/json-loads (:body resp))))
                             204 res))))
        (dissoc :ids)))))

(defspec server-spec (dq-test/times 100)
  (prop/for-all [ops dq-test/gen-ops-seq]
    (is (= (dq-test/expected ops)
           (actual ops)))))
