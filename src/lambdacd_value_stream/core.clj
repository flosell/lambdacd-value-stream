(ns lambdacd-value-stream.core
  (:require [lambdacd.steps.support :as support]
            [clojure.core.async :as async]
            [lambdacd.event-bus :as event-bus]))

(defn- kill-switch->ch [ctx]
  (let [ch       (async/chan)
        notifier (fn [key reference old new]
                   (if (and (not= old new)
                            (= true new))
                     (async/>!! ch :killed)))]
    (add-watch (:is-killed ctx) ::to-channel-watcher notifier)
    ch))

(defn- clean-up-kill-switch->ch [ctx]
  (remove-watch (:is-killed ctx) ::to-channel-watcher))

(defn- report-waiting-status [ctx]
  (async/>!! (:result-channel ctx) [:status :waiting]))

(defn- filter-ch [c f]
  (let [filtering-chan (async/chan 1 (filter f))]
    (async/pipe c filtering-chan)
    filtering-chan))

(defn- filter-for-pipeline-id [pipeline-id c]
  (filter-ch c #(= pipeline-id (:pipeline-id %))))

(defn- filter-for-triggered-pipeline [ctx c]
  (let [own-pipeline-id (get-in ctx [:config :value-stream :pipeline-id])]
  (filter-ch c #(= own-pipeline-id (:pipeline-to-trigger %)))))

(defn- pipeline-success-event->step-result [msg]
  {:status                :success
   :upstream-build-number (:build-number msg)
   :upstream-result       (:final-result msg)})

(defn- pipeline-trigger-event->step-result [msg]
  (assoc msg :status :success))

(defn- wait-for-pipeline-event [ctx event-type filter-fn event-converter]
  (let [kill-channel             (kill-switch->ch ctx)
        subscription             (event-bus/subscribe ctx event-type)
        pipeline-success-channel (->> subscription
                                      (event-bus/only-payload)
                                      (filter-fn))
        _                        (report-waiting-status ctx)
        result                   (async/alt!!
                                   kill-channel {:status :killed}
                                   pipeline-success-channel ([msg] (event-converter msg)))]

    (event-bus/unsubscribe ctx ::pipeline-success subscription)
    (clean-up-kill-switch->ch ctx)
    result))

(defn wait-for-pipline-success [pipeline-id ctx]
  (support/capture-output ctx
    (println "Waiting for upstream build" pipeline-id "to finish successfully")
    (let [result (wait-for-pipeline-event ctx ::pipeline-success (partial filter-for-pipeline-id pipeline-id) pipeline-success-event->step-result)]
      (if (= :success (:status result))
        (println "Upstream build" (:upstream-build-number result) "finished successfully."))
      result)))

(defn wait-for-upstream-trigger [_ ctx]
  (support/capture-output ctx
    (println "Waiting to be triggered by upstream pipeline...")
    (let [result (wait-for-pipeline-event ctx ::upstream-trigger (partial filter-for-triggered-pipeline ctx) pipeline-trigger-event->step-result)]
      (if (= :success (:status result))
        (println "Upstream build" (:upstream-build-number result) "triggered this build."))
      result)))

(defn trigger-downstream-pipeline [pipeline-to-trigger args ctx]
  (event-bus/publish ctx ::trigger-downstream {:pipeline-to-trigger pipeline-to-trigger
                                               :upstream-pipeline-id (get-in ctx [:config :value-stream :pipeline-id])
                                               :upstream-args args
                                               :upstream-build-number (:build-number ctx)
                                               :upstream-step-id (:step-id ctx)})
  {:status :success
   :out (str "Triggering " pipeline-to-trigger)})

(defn- last-pipeline-step? [pipeline]
  (let [last-step-id [(count pipeline)]]
    (fn [msg]
      (= last-step-id (:step-id msg)))))

(defn- successful-result? [msg]
  (= :success (get-in msg [:final-result :status])))

(defn- to-pipeline-success-event [msg]
  {:topic   ::pipeline-success
   :payload msg})

(defn- to-upstream-trigger-event [msg]
  {:topic   ::upstream-trigger
   :payload msg})

(defn- transduced-ch [c xs]
  (let [result (async/chan 1 xs)]
    (async/pipe c result)
    result))

(defn- pipeline-success-ch [pipeline]
  (let [pipeline-id (get-in pipeline [:context :config :value-stream :pipeline-id])]
    (-> (event-bus/subscribe (:context pipeline) :step-finished)
        (event-bus/only-payload)
        (transduced-ch (comp
                         (filter (last-pipeline-step? pipeline))
                         (filter successful-result?)
                         (map (fn [msg] {:pipeline-id  pipeline-id
                                         :build-number (:build-number msg)
                                         :final-result (:final-result msg)}))
                         (map to-pipeline-success-event))))))

(defn- downstream-trigger-ch [pipeline]
  (-> (event-bus/subscribe (:context pipeline) ::trigger-downstream)
      (event-bus/only-payload)
      (transduced-ch
        (map to-upstream-trigger-event))))

(defn broadcast-ch! [src-ch out-chs]
  (let [multiple (async/mult src-ch)]
    (doall
      (map (fn [out-ch]
             (async/tap multiple out-ch)) out-chs))))

(defn- legacy-fallback [input]
  (map (fn [[pipeline-id pipeline]]
         (assoc-in pipeline [:context :config :value-stream :pipeline-id] pipeline-id ))
       input))

(defn initialize-value-stream [input]
  (let [pipelines              (if (map? input)
                                 (legacy-fallback input)
                                 input)
        pipeline-success-chs   (map pipeline-success-ch pipelines)
        downstream-trigger-chs (map downstream-trigger-ch pipelines)
        interesting-events-ch  (async/merge (concat pipeline-success-chs downstream-trigger-chs))
        get-event-publisher-ch #(get-in % [:context :event-publisher])
        publisher-chs          (map get-event-publisher-ch pipelines)]
    (broadcast-ch! interesting-events-ch publisher-chs)))

; TODO: when to unsubscribe?