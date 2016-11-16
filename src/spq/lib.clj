(ns spq.lib
  (:require [cheshire.core :as json]
            [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.java
             [io :as io]
             [shell :as sh]]
            [durable-queue :as dq]
            [taoensso.timbre :as timbre]))

(def *kill* (doto (not= \n #spy/p (first (System/getenv "KILL")))
              (->> (println "*kill*"))))

(defn run [& args]
  (let [cmd (apply str (interpose " " args))
        res (clojure.java.shell/sh "bash" "-c" cmd)]
    (assert (-> res :exit (= 0)) (assoc res :cmd cmd))
    (.trim (:out res))))

(defn shutdown
  []
  (when *kill*
    (timbre/fatal "shutdown. if jvm doesnt exit right after this, it should have")
    (Thread/sleep 1000)
    (shutdown-agents)
    (System/exit 0) ;; soft kill
    (run "ps h -o ppid $$|xargs sudo kill -9") ;;  hard kill
    nil))

(defmacro go-supervised
  [name & forms]
  `(a/go
     (try
       ~@forms
       (catch Throwable e#
         (timbre/error e# "supervised go-block excepted:" ~name)
         (when *kill*
           (timbre/error "going to exit because supervised go-block" ~name "excepted and *kill* was set")
           (shutdown))))))

(defn json-dumps
  [x]
  (json/generate-string x))

(defn json-loads
  [x]
  (json/parse-string x true))

(def queue-path (str (System/getProperty "user.home") "/public-queue"))

(defn open-queue
  []
  (timbre/info "opening queues at path:" queue-path)
  (dq/queues queue-path {:fsync-put? true :fsync-take? true}))

(defn uuid
  []
  (str (java.util.UUID/randomUUID)))

(defn deref-task
  [task]
  (try
    @task
    (catch java.io.IOException ex
      (timbre/fatal ex "failed to read queued item from disk")
      (shutdown))))

(defn abbreviate
  [x]
  (apply str (take 250 (str x))))
