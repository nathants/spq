(defproject spq "0.1.0-SNAPSHOT"
  :description "simple persistent queue"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [com.taoensso/timbre "4.10.0"]
                 [factual/durable-queue "0.1.5"]
                 [clj-http "3.5.0"]
                 [aleph "0.4.4-alpha4"]
                 [compojure "1.6.0"]
                 [cheshire "5.6.3"]
                 [snapshots/confs "master-a8ee32c"]]
  :main spq.server
  :profiles {:dev {:dependencies [[org.clojure/test.check "0.9.0"]]}}
  :uberjar-name "spq.jar"
  :plugins [[s3-wagon-private "1.3.0-alpha2"]]
  :repositories {"snapshots" {:url ~(System/getenv "S3P_SNAPSHOTS_URL")
                              ;; snapshots built by bin/build_libs.sh,
                              ;; with an env var like:
                              ;; S3P_SNAPSHOTS_URL=s3p://$BUCKET/software/
                              ;;
                              ;; alternatively, install snapshots
                              ;; locally with bin/install_libs.sh.
                              :username :env/aws_access_key_id
                              :passphrase :env/aws_secret_access_key
                              :update :never
                              :checksum :never}}
  :jvm-opts ^:replace ["-server"])
