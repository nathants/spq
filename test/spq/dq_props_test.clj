(ns spq.dq-props-test
  (:require [clojure.test :refer :all]
            [clojure.test.check
             [clojure-test :refer [defspec]]
             [generators :as gen]
             [properties :as prop]]
            [durable-queue :as dq]
            [spq.lib :as lib]))

(defn tempdir
  []
  (lib/run "mktemp -d"))

(defn times
  [n]
  (-> "TEST_CHECK_FACTOR" System/getenv (or "1") Double/parseDouble (* n) long))

(def gen-int
  (gen/scale #(/ % 15) gen/int))

(def gen-ops
  (gen/one-of [(gen/tuple (gen/return :retry!) gen-int)
               (gen/tuple (gen/return :complete!) gen-int)
               (gen/tuple (gen/return :take!) gen-int)
               (gen/tuple (gen/return :put!) (gen/map (gen/return :value) gen/int {:num-elements 1}))]))

(defn valid?
  []
  (let [seen (volatile! #{})]
    (fn [[op id]]
      (condp = op
        :retry!    (@seen id)
        :complete! (@seen id)
        :take!     (not (@seen id))
        :put!      true))))

(def gen-ops-seq
  (gen/let [ops (gen/vector gen-ops)]
    (filter (valid?) ops)))

(defn expected
  [ops]
  (reduce (fn [acc [op x]]
            (condp = op
              :put! (update-in acc [:queued] concat [x])
              :take! (if-not (seq (:queued acc))
                       acc
                       (-> acc
                         (assoc-in [:taken x] (first (:queued acc)))
                         (update-in [:queued] rest)))
              :complete! (if-not ((:taken acc) x)
                           acc
                           (-> acc
                             (assoc-in [:completed x] ((:taken acc) x))
                             (update-in [:taken] dissoc x)))
              :retry! (if-not ((:taken acc) x)
                        acc
                        (-> acc
                          (update-in [:queued] concat [x])
                          (update-in [:taken] dissoc x)))))
          {:queued ()
           :taken {}
           :completed {}}
          ops))

(defn actual
  [ops]
  (let [dir (tempdir)
        name "test_queue"
        q (dq/queues dir {:fsync-put? false
                          :fsync-take? false})
        f (fn [acc [op x]]
            (condp = op
              :put! (do (dq/put! q name x)
                        acc)
              :take! (let [v (dq/take! q name 0 ::empty)]
                       (if (= ::empty v)
                         acc
                         (assoc-in acc [:taken x] v)))
              :complete! (if-let [v ((:taken acc) x)]
                           (do (dq/complete! v)
                               (-> acc
                                 (assoc-in [:completed x] @v)
                                 (update-in [:taken] dissoc x)))
                           acc)
              :retry! (if-let [v ((:taken acc) x)]
                        (do (dq/retry! v)
                            (update-in acc [:taken] dissoc x))
                        acc)))]
    (try
      (-> (reduce f {:taken {} :completed {}} ops)
        (update-in [:taken] #(->> %
                               (mapv (fn [[k v]]
                                       [k @v]))
                               (into {})))
        (assoc :queued (mapv deref (dq/immediate-task-seq q name))))
      (finally
        (lib/run "rm -rf" tempdir)))))

(defspec dq-spec (times 100)
  (prop/for-all [ops gen-ops-seq]
    (is (= (expected ops)
           (actual ops)))))
