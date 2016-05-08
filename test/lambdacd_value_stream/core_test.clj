(ns lambdacd-value-stream.core-test
  (:require [clojure.test :refer :all]
            [lambdacd-value-stream.core :as core]
            [lambdacd.internal.pipeline-state :as pipeline-state]
            [clojure.core.async :as async]
            [lambdacd.core :as lambdacd-core]
            [lambdacd-value-stream.test_utils :refer [some-ctx-with some-ctx read-channel-or-time-out slurp-chan-with-size]]
            [lambdacd.event-bus :as event-bus]))

; SYNTACTIC SUGAR FIRST. SCROLL DOWN FOR TESTS

(def wait-for-step-finished 10000)

(defn- status-updates-channel [ctx]
  (let [step-result-updates-ch (event-bus/only-payload
                                 (event-bus/subscribe ctx :step-result-updated))
        only-status-updates    (async/chan 100 (map #(get-in % [:step-result :status])))]
    (async/pipe step-result-updates-ch only-status-updates)
    only-status-updates))

(defn- init-state []
  (let [is-killed           (atom false)
        ctx                 (some-ctx-with :is-killed is-killed)
        step-status-channel (status-updates-channel ctx)]
    (pipeline-state/start-pipeline-state-updater (:pipeline-state-component ctx) ctx)
    (atom {:ctx                 ctx
           :is-killed           is-killed
           :step-status-channel step-status-channel})))

(defn wait-for-step-waiting [state]
  (let [step-status-ch (:step-status-channel @state)]
    (read-channel-or-time-out
      (async/go
        (loop []
          (let [status (async/<! step-status-ch)]
            (if-not (= :waiting status)
              (recur)))))))
  state)

(defn start-wait-for-pipeline-success [state & {:keys [pipeline-to-wait-for] :or {pipeline-to-wait-for :some-pipeline}}]
  (let [wait-for-result-channel (async/go
                                  (let [execute-step-result (lambdacd-core/execute-step {} (:ctx @state)
                                                                                        (fn [args ctx]
                                                                                          (core/wait-for-pipline-success pipeline-to-wait-for ctx)))]
                                    (first (vals (:outputs execute-step-result)))))]
    (swap! state #(assoc % :result-channel wait-for-result-channel))
    (wait-for-step-waiting state)
    state))

(defn kill-waiting-step [state]
  (reset! (:is-killed @state) true)
  state)

(defn get-step-result [state & {:keys [timeout] :or {timeout wait-for-step-finished}}]
  (try
    (let [result (read-channel-or-time-out (:result-channel @state) :timeout timeout)]
      (swap! state #(assoc % :step-result result))
      state)
    (catch Exception e
      (kill-waiting-step state)
      (throw e))))

(defn wait-for-step-to-complete [state & args]
  ; just an alias
  (apply get-step-result state args))

(defn step-result [state]
  (:step-result @state))

(defn- notify-about-pipeline-success [state & {:keys [pipeline-id build-number final-result] :or {pipeline-id  :some-pipeline
                                                                                                  build-number 1
                                                                                                  final-result {}}}]
  (event-bus/publish (:ctx @state) ::core/pipeline-success {:pipeline-id  pipeline-id
                                                            :build-number build-number
                                                            :final-result final-result})
  state)


; TESTS:

(deftest wait-for-pipeline-success-test
  (testing "that it finishes once it gets notified the pipeline is done"
    (let [state (-> (init-state)
                    (start-wait-for-pipeline-success :pipeline-to-wait-for :some-pipeline)
                    (notify-about-pipeline-success :pipeline-id :some-pipeline)
                    (get-step-result))]
      (is (= :success (:status (step-result state))))))
  (testing "that it ignores successes on other pipelines"
    (let [state (-> (init-state)
                    (start-wait-for-pipeline-success :pipeline-to-wait-for :some-pipeline)
                    (notify-about-pipeline-success :pipeline-id :some-other-pipeline))]
      (is (thrown? Exception (wait-for-step-to-complete state :timeout 500)))))
  (testing "that it can access data from the other pipeline"
    (let [state (-> (init-state)
                    (start-wait-for-pipeline-success :pipeline-to-wait-for :some-pipeline)
                    (notify-about-pipeline-success :pipeline-id :some-pipeline
                                                   :build-number 42
                                                   :final-result {:foo :bar})
                    (get-step-result))]
      (is (= 42 (:upstream-build-number (step-result state))))
      (is (= {:foo :bar} (:upstream-result (step-result state))))))

  (testing "that wait-for can be killed"
    (let [state (-> (init-state)
                    (start-wait-for-pipeline-success)
                    (kill-waiting-step)
                    (get-step-result))]
      (is (= :killed (:status (step-result state)))))))

(defn step-1 [args ctx])
(defn step-2 [args ctx])

(def some-pipeline-structure
  `(
     step-1
     step-2))

(defn- mock-assemble-pipeline [pipeline-structure]
  {:context            (some-ctx)
   :pipeline-structure pipeline-structure})

(deftest initialize-value-stream-test
  (testing "that it publishes information about pipeline-successes on all registered event buses"
    (let [pipeline-one                       (mock-assemble-pipeline some-pipeline-structure)
          pipeline-two                       (mock-assemble-pipeline some-pipeline-structure)
          pipeline-two-success-event-channel (-> (:context pipeline-two)
                                                 (event-bus/subscribe ::core/pipeline-success)
                                                 (event-bus/only-payload))
          pipeline-one-success-event-channel (-> (:context pipeline-one)
                                                 (event-bus/subscribe ::core/pipeline-success)
                                                 (event-bus/only-payload))]
      (core/initialize-value-stream {:one pipeline-one
                                     :two pipeline-two})
      (Thread/sleep 500) ; HACK to make sure everything is properly subscribed and tapped
      (event-bus/publish (:context pipeline-one) :step-finished {:step-id      [1]
                                                                 :build-number :some-other-build-number
                                                                 :final-result {:shouldnt :matter}})
      (event-bus/publish (:context pipeline-one) :step-finished {:step-id      [2]
                                                                 :build-number :some-failing-build
                                                                 :final-result {:status :failure}})
      (event-bus/publish (:context pipeline-one) :step-finished {:step-id      [2]
                                                                 :build-number :some-build-number
                                                                 :final-result {:status :success}})
      (is (= [{:pipeline-id  :one
               :build-number :some-build-number
               :final-result {:status :success}}] (slurp-chan-with-size 1 pipeline-one-success-event-channel)))
      (is (= [{:pipeline-id  :one
               :build-number :some-build-number
               :final-result {:status :success}}] (slurp-chan-with-size 1 pipeline-two-success-event-channel))))))