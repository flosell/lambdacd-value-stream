(ns lambdacd-value-stream.core
  (:require [lambdacd.steps.support :as support]
            [clojure.core.async :as async]))

(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!")
  x)


(defn wait-for-pipline-success [pipeline ctx]
  (support/capture-output ctx
    (do
      (println "NOT IMPLEMENTED YET")
      (println "Will wait forever. But one day it'll wait until pipeline" pipeline "finished successfully")
      (loop []
        (support/if-not-killed ctx
          (do
            (async/>!! (:result-channel ctx) [:status :waiting])
            (Thread/sleep (* 1 1000))
            (recur)))))))