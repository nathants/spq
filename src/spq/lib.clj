(ns spq.lib
  (:require [cheshire.core :as json]
            [clojure.stacktrace :as st]
            [clojure.java.shell :as sh]
            [confs.core :as confs :refer [conf]]
            [durable-queue :as dq]
            [taoensso.timbre :as timbre]))

(def *kill* (doto (not= \n (first (System/getenv "KILL")))
              (->> (println "kill:"))))

(defn run [& args]
  (let [cmd (apply str (interpose " " args))
        res (clojure.java.shell/sh "bash" "-c" cmd)]
    (assert (-> res :exit (= 0)) (assoc res :cmd cmd))
    (.trim (:out res))))

(defn setup-logging
  [& [short-format]]
  (timbre/merge-config!
   {:appenders
    {:println
     {:fn (let [atomic-print (fn [x]
                               (print x)
                               (flush))]
            (if short-format
              #(let [{:keys [msg_ ?err_]} %]
                 (atomic-print
                  (str
                   (force msg_)
                   " "
                   (if-let [err (force ?err_)]
                     (str "\n" (st/print-stack-trace err))
                     "")
                   "\n")))
              #(let [{:keys [msg_ hostname_ timestamp_ instant level ?ns-str ?err_]} %]
                 (atomic-print
                  (str
                   (.toString (.toInstant instant))
                   " "
                   level
                   " "
                   (force ?ns-str)
                   " "
                   (force hostname_)
                   " "
                   (force msg_)
                   " "
                   (if-let [err (force ?err_)]
                     (str "\n" (st/print-stack-trace err))
                     "")
                   "\n")))))}}}))

(defn shutdown
  []
  (when *kill*
    (timbre/warn "shutdown. if jvm doesnt exit right after this, it should have")
    (Thread/sleep 1000)
    (shutdown-agents)
    (System/exit 0) ;; soft kill
    (run "ps h -o ppid $$|xargs sudo kill -9") ;;  hard kill
    nil))

(defn json-dumps
  [x]
  (json/generate-string x))

(defn json-loads
  [x]
  (json/parse-string x true))

(def queue-path (str (System/getProperty "user.home") "/queue-data"))

(defn open-queue
  []
  (timbre/info "opening queues at path:" queue-path)
  (dq/queues queue-path (conf :durable-queue)))

(defn uuid
  []
  (str (java.util.UUID/randomUUID)))

(defn deref-task
  [task]
  (try
    @task
    (catch java.io.IOException ex
      (timbre/error ex "failed to read queued item from disk")
      nil)))

(defn abbreviate
  [x]
  (apply str (take 250 (str x))))

(defn minutes-ago
  [nano-time]
  (-> (System/nanoTime) (- nano-time) double (/ 1000000000.0 60.0)))
