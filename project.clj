(def lambdacd-version (or
                        (System/getenv "LAMBDACD_VERSION")
                        "0.13.5"))

(def clojure-version-to-use (or
                              (System/getenv "CLOJURE_VERSION")
                              "1.7.0"))

(defproject lambdacd-value-stream "0.2.0"
  :description "A library that adds upstream and downstream triggers to LambdaCD"
  :url "https://github.com/flosell/lambdacd-value-stream"
  :license {:name "Apache License, version 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :test-paths ["test" "example"]
  :dependencies [[org.clojure/clojure ~clojure-version-to-use]
                 [lambdacd ~lambdacd-version]]
  :deploy-repositories [["clojars" {:creds :gpg}]
                        ["releases" :clojars]]
  :profiles {:dev {:dependencies [[compojure "1.6.0"]
                                  [lambdacd-git "0.4.1"]
                                  [ring-server "0.4.0"]]}})
