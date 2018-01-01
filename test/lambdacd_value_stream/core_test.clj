(ns lambdacd-value-stream.core-test
  (:require [clojure.test :refer :all]
            [lambdacd-value-stream.core :as core]
            [lambdacd.state.internal.pipeline-state-updater :as pipeline-state]
            [clojure.core.async :as async]
            [lambdacd.core :as lambdacd-core]
            [lambdacd-value-stream.test_utils :refer [some-ctx-with read-channel-or-time-out slurp-chan-with-size map-containing create-temp-dir]]
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
        ctx                 (some-ctx-with :is-killed is-killed
                                           :config {:value-stream {:pipeline-id :some-pipeline-id}
                                                    :home-dir     (create-temp-dir)})
        step-status-channel (status-updates-channel ctx)]
    (pipeline-state/start-pipeline-state-updater ctx)
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

(defn start-wait-for-upstream-trigger [state]
  (let [wait-for-result-channel (async/go
                                  (let [execute-step-result (lambdacd-core/execute-step {} (:ctx @state)
                                                                                        (fn [args ctx]
                                                                                          (core/wait-for-upstream-trigger args ctx)))]
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
  (event-bus/publish!! (:ctx @state) ::core/pipeline-success {:pipeline-id  pipeline-id
                                                            :build-number build-number
                                                            :final-result final-result})
  state)

(defn notify-about-upstream-pipeline-trigger [state & {:as partial-event}]
  (event-bus/publish!! (:ctx @state) ::core/upstream-trigger (merge
                                                             {:pipeline-to-trigger   :some-pipeline-id
                                                              :upstream-args         {:some :args}
                                                              :upstream-build-number :some-build-number
                                                              :upstream-pipeline-id  :some-upstream-pipeline-id
                                                              :upstream-step-id      :some-step-id}
                                                             partial-event))
  state)


(defn step-1 [args ctx])
(defn step-2 [args ctx])

(def some-pipeline-structure
  `(
     step-1
     step-2))

(defn- mock-assemble-pipeline [pipeline-structure config]
  {:context            (some-ctx-with :config (assoc config :home-dir (create-temp-dir)))
   :pipeline-structure pipeline-structure})


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

(deftest wait-for-upstream-trigger-test
  (testing "that we can trigger a downstream pipeline from an upstream one"
    (let [state (-> (init-state)
                    (start-wait-for-upstream-trigger)
                    (notify-about-upstream-pipeline-trigger)
                    (get-step-result))]
      (is (map-containing {:status :success} (step-result state)))))
  (testing "that it ignores triggers meant for other pipelines"
    (let [state (-> (init-state)
                    (start-wait-for-upstream-trigger)
                    (notify-about-upstream-pipeline-trigger :pipeline-to-trigger :some-other-pipeline))]
      (is (thrown? Exception (wait-for-step-to-complete state :timeout 500)))))
  (testing "that it can access data from the other pipeline"
    (let [state (-> (init-state)
                    (start-wait-for-upstream-trigger)
                    (notify-about-upstream-pipeline-trigger :upstream-args {:foo :bar}
                                                            :upstream-build-number 42
                                                            :upstream-step-id [1 2]
                                                            :upstream-pipeline-id :foo)
                    (get-step-result))]
      (is (map-containing {:upstream-build-number 42} (step-result state)))
      (is (map-containing {:upstream-args {:foo :bar}} (step-result state)))
      (is (map-containing {:upstream-step-id [1 2]} (step-result state)))
      (is (map-containing {:upstream-pipeline-id :foo} (step-result state))))))

(deftest trigger-downstream-test
  (testing "that it publishes an event that gets broadcasted by initialize-value-stream"
    (let [ctx        (some-ctx-with :build-number :some-build-number
                                    :step-id :some-step-id
                                    :config {:value-stream {:pipeline-id :some-upstream-pipeline-id}
                                             :home-dir (create-temp-dir)})
          trigger-ch (-> ctx
                         (event-bus/subscribe ::core/trigger-downstream)
                         (event-bus/only-payload))]
      (is (map-containing {:status :success} (core/trigger-downstream-pipeline :some-downstream-pipeline-id {:some :args} ctx)))
      (is (= [{:pipeline-to-trigger   :some-downstream-pipeline-id
               :upstream-args         {:some :args}
               :upstream-build-number :some-build-number
               :upstream-pipeline-id  :some-upstream-pipeline-id
               :upstream-step-id      :some-step-id}] (slurp-chan-with-size 1 trigger-ch))))))


(deftest initialize-value-stream-test
  (let [pipeline-one        (mock-assemble-pipeline some-pipeline-structure {:value-stream {:pipeline-id :one}})
        pipeline-two        (mock-assemble-pipeline some-pipeline-structure {:value-stream {:pipeline-id :two}})
        pipeline-one-legacy (mock-assemble-pipeline some-pipeline-structure {})
        pipeline-two-legacy (mock-assemble-pipeline some-pipeline-structure {})

        legacy-testcase     {:pipeline-one pipeline-one-legacy
                             :pipeline-two pipeline-two-legacy
                             :input        {:one pipeline-one-legacy
                                            :two pipeline-two-legacy}}
        new-testcase        {:pipeline-one pipeline-one
                             :pipeline-two pipeline-two
                             :input        [pipeline-one pipeline-two]}]
    (doall (for [testcase [legacy-testcase new-testcase]]
             (let [{pipeline-one :pipeline-one pipeline-two :pipeline-two pipeline-config :input} testcase]
               (core/initialize-value-stream pipeline-config)
               (testing "that it publishes information about pipeline-successes on all registered event buses"
                 (let [pipeline-two-success-event-channel (-> (:context pipeline-two)
                                                              (event-bus/subscribe ::core/pipeline-success)
                                                              (event-bus/only-payload))
                       pipeline-one-success-event-channel (-> (:context pipeline-one)
                                                              (event-bus/subscribe ::core/pipeline-success)
                                                              (event-bus/only-payload))]
                   (Thread/sleep 500)                       ; HACK to make sure everything is properly subscribed and tapped
                   (event-bus/publish!! (:context pipeline-one) :step-finished {:step-id      [1]
                                                                              :build-number :some-other-build-number
                                                                              :final-result {:shouldnt :matter}})
                   (event-bus/publish!! (:context pipeline-one) :step-finished {:step-id      [2]
                                                                              :build-number :some-failing-build
                                                                              :final-result {:status :failure}})
                   (event-bus/publish!! (:context pipeline-one) :step-finished {:step-id      [2]
                                                                              :build-number :some-build-number
                                                                              :final-result {:status :success}})
                   (is (= [{:pipeline-id  :one
                            :build-number :some-build-number
                            :final-result {:status :success}}] (slurp-chan-with-size 1 pipeline-one-success-event-channel)))
                   (is (= [{:pipeline-id  :one
                            :build-number :some-build-number
                            :final-result {:status :success}}] (slurp-chan-with-size 1 pipeline-two-success-event-channel)))))
               (testing "that it publishes downstream triggers on all registered event buses"
                 (let [pipeline-two-success-event-channel (-> (:context pipeline-two)
                                                              (event-bus/subscribe ::core/upstream-trigger)
                                                              (event-bus/only-payload))
                       pipeline-one-success-event-channel (-> (:context pipeline-one)
                                                              (event-bus/subscribe ::core/upstream-trigger)
                                                              (event-bus/only-payload))]
                   (Thread/sleep 500)                       ; HACK to make sure everything is properly subscribed and tapped
                   (event-bus/publish!! (:context pipeline-one) ::core/trigger-downstream {:some :info})
                   (is (= [{:some :info}] (slurp-chan-with-size 1 pipeline-one-success-event-channel)))
                   (is (= [{:some :info}] (slurp-chan-with-size 1 pipeline-two-success-event-channel))))))))))
