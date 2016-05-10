(def lambdacd-version (or
                        (System/getenv "LAMBDACD_VERSION")
                        "0.9.0"))

(defproject lambdacd-value-stream "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "https://github.com/flosell/lambdacd-value-stream"
  :license {:name "Apache License, version 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :test-paths ["test" "example"]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [lambdacd ~lambdacd-version]]
  :profiles {:dev {:dependencies [[compojure "1.1.8"]
                                  [lambdacd-git "0.1.2"]
                                  [ring-server "0.4.0"]]}})