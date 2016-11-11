(ns spq.lib
  (:require [clojure.java.shell :as sh]
            [clojure.string :as s]
            [clojure.edn :as edn]))

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

;; (defn keys-in
;;   [m]
;;   (->> ((fn f [k v]
;;           (->> (if (map? v)
;;                  (map #(f (cons (first %) k) (second %)) v)
;;                  [k ::end])))
;;         nil m)
;;        flatten
;;        (partition-by #(= % ::end))
;;        (remove #(= % [::end]))
;;        (map reverse)))

;; (defn load-confs
;;   [& paths]
;;   (let [confs (mapv #(edn/read-string (slurp %)) paths)]
;;     (or (= 1 (count paths))
;;         (let [conf (apply load-confs (drop 1 paths))]
;;           (mapv #(apply conf %) (keys-in (first confs)))))
;;     (with-meta
;;       (fn [& ks]
;;         (doto (->> confs (map #(get-in % ks)) (remove nil?) first)
;;           (-> nil? not (assert (str "there is no value for keys " (vec ks) " in paths " (vec paths))))))
;;       {:confs confs})))

;; (defn load-confs-edn-str
;;   [edn-str & paths]
;;   (if-not (edn/read-string edn-str)
;;     (apply load-confs paths)
;;     (let [path (.getAbsolutePath (java.io.File/createTempFile "temp" ""))]
;;       (spit path edn-str)
;;       (apply load-confs (cons path paths)))))
