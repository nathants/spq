(ns spq.lib
  (:require [clojure.java.shell :as sh]
            [clojure.string :as s]))

(defn run [& args]
  (let [cmd (apply str (interpose " " args))
        res (sh/sh "bash" "-c" cmd)]
    (assert (-> res :exit (= 0)) (assoc res :cmd cmd))
    (.trim (:out res))))


(defn drop-trailing-slash
  [path]
  (s/replace path #"/$" ""))

(defn ensure-trailing-slash
  [path]
  (str (drop-trailing-slash path) "/"))

(defn parts->path
  [parts]
  (s/join "/" parts))

(defn path->parts
  [path]
  (remove s/blank? (s/split path #"/")))

(defn dirname
  [path]
  (-> path path->parts butlast parts->path))

(defn basename
  [path]
  (-> path path->parts last))

(defn uuid
  []
  (str (java.util.UUID/randomUUID)))
