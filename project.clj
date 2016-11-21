(defproject spq "0.1.0-SNAPSHOT"
  :description "simple persistent queue"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.taoensso/timbre "4.7.4"]
                 [org.clojure/core.async "0.2.395"]
                 [factual/durable-queue "0.1.5"]
                 [clj-http "3.3.0"]
                 [aleph "0.4.1"]
                 [compojure "1.6.0-beta1"]
                 [cheshire "5.6.3"]
                 [snapshots/confs "master-22e882e"]]
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
