(defproject spq "0.1.0-SNAPSHOT"
  :description "simple persistent queue"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [
                 [org.clojure/clojure "1.8.0"]
                 [com.taoensso/timbre "4.7.4"]
                 [org.clojure/core.async "0.2.395"]
                 [factual/durable-queue "0.1.5"]
                 [clj-http "3.3.0"]
                 [aleph "0.4.1"]
                 [compojure "1.6.0-beta1"]
                 [cheshire "5.6.3"]
                 [pandect "0.6.1"]
                 [schema "0.1.0-SNAPSHOT"]]
  :uberjar-name "spq.jar"
  ;; :profiles {:uberjar {:aot :all}}
  :jvm-opts ^:replace ["-server"])
