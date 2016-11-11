(ns spq.queue
  (:require [durable-queue :as dq]
            [taoensso.timbre :as timbre]))

(defn public-queue
  []
  (let [path (str (System/getProperty "user.home") "/public-queue")]
    (timbre/info "opening queues at path:" path)
    (dq/queues path {:fsync-put? true :fsync-take? true})))

(defn private-queue
  []
  (let [path (str (System/getProperty "user.home") "/private-queue")]
    (timbre/info "opening queues at path:" path)
    (dq/queues path {:fsync-put? true :fsync-take? true})))
