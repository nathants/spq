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

(defmacro go-supervised
  [name & forms]
  `(a/go
     (try
       ~@forms
       (catch Throwable e#
         (timbre/error e# "supervised go-block excepted:" ~name)
         (when *kill*
           (timbre/error "going to exit because supervised go-block" ~name "excepted and *kill* was set")
           (System/exit 1))))))

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

(defn -keys-in
  [m]
  (->> ((fn f [k v]
          (->> (if (map? v)
                 (map #(f (cons (first %) k) (second %)) v)
                 [k ::end])))
        nil m)
    flatten
    (partition-by #(= % ::end))
    (remove #(= % [::end]))
    (map reverse)))

(defn -load-confs
  [& paths]
  (let [confs (mapv #(edn/read-string (slurp %)) paths)]
    (or (= 1 (count paths))
        (let [conf (apply -load-confs (drop 1 paths))]
          (mapv #(apply conf %) (-keys-in (first confs)))))
    (with-meta
      (fn [& ks]
        (doto (->> confs (map #(get-in % ks)) (remove nil?) first)
          (-> nil? not (assert (str "there is no value for keys " (vec ks) " in paths " (vec paths))))))
      {:confs confs})))

(defn load-confs
  [& paths]
  (let [paths (if (empty? paths)
                ["{}"]
                paths)
        last-is-str (->> paths first io/as-file .exists not)
        edn-str (if last-is-str
                  (first paths)
                  "")
        paths (if last-is-str
                (rest paths)
                paths)]
    (if-not (edn/read-string edn-str)
      (apply -load-confs paths)
      (let [path (.getAbsolutePath (java.io.File/createTempFile "temp" ""))]
        (spit path edn-str)
        (apply -load-confs (cons path paths))))))
