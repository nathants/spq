(ns spq.async
  (:require [clojure.core.async :as a]
            [taoensso.timbre :as timbre]))

(def ^:dynamic *kill* true)

(defmacro supervise
  [name & forms]
  `(try
     ~@forms
     (catch Throwable e#
       (timbre/error e# "supervised go block excepted:" ~name)
       (when *kill*
         (timbre/error "going to exit because" ~name "excepted and asked for *kill*")
         (System/exit 1)))))

(defmacro go-supervised
  [name & forms]
  `(a/go
     (supervise ~name
       ~@forms)))

(defmacro go-loop-supervised
  [name binding & forms]
  `(go-supervised ~name
     (loop ~binding ~@forms)))
