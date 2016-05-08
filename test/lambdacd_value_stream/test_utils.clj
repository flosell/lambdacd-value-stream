(ns lambdacd-value-stream.test_utils
  (:require [lambdacd.internal.default-pipeline-state :as default-pipeline-state]
            [lambdacd.event-bus :as event-bus]
            [clojure.core.async :as async]
            [lambdacd.util :as utils]))

(defn- some-ctx-template []
  (let [config {:home-dir (utils/create-temp-dir)}]
    (-> {:initial-pipeline-state   {} ;; only used to assemble pipeline-state, not in real life
         :step-id                  [42]
         :result-channel           (async/chan (async/dropping-buffer 100))
         :pipeline-state-component nil ;; set later
         :config                   config
         :is-killed                (atom false)
         :_out-acc                 (atom "")}
        (event-bus/initialize-event-bus))
    ))

(defn- add-pipeline-state-component [template]
  (if (nil? (:pipeline-state-component template))
    (assoc template :pipeline-state-component
                    (default-pipeline-state/new-default-pipeline-state template :initial-state-for-testing (:initial-pipeline-state template)))
    template))

(defn some-ctx-with [& args]
  (add-pipeline-state-component
    (apply assoc (some-ctx-template) args)))
(defn some-ctx [& args]
  (add-pipeline-state-component
    (some-ctx-template)))

(defn read-channel-or-time-out [c & {:keys [timeout]
                                     :or             {timeout 10000}}]
  (async/alt!!
    c ([result] result)
    (async/timeout timeout) (throw (Exception. "timeout!"))))

  (defn slurp-chan-with-size [size ch]
  (read-channel-or-time-out
    (async/go-loop [collector []]
      (if-let [item (async/<! ch)]
        (let [new-collector (conj collector item)]
          (if (= size (count new-collector))
            new-collector
            (recur new-collector)))))))