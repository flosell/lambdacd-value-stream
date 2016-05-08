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

(defn wait-for-pipline-success [pipeline-id ctx]
  (support/capture-output ctx
    (println "Waiting for upstream build" pipeline-id "to finish successfully")
    (let [kill-channel             (kill-switch->ch ctx)
          subscription             (event-bus/subscribe ctx ::pipeline-success)
          pipeline-success-channel (->> subscription
                                        (event-bus/only-payload)
                                        (filter-for-pipeline-id pipeline-id))
          _                        (report-waiting-status ctx)
          result                   (async/alt!!
                                     kill-channel {:status :killed}
                                     pipeline-success-channel ([msg] {:status                :success
                                                                      :upstream-build-number (:build-number msg)
                                                                      :upstream-result       (:final-result msg)}))]
      (if (= :success (:status result))
        (println "Upstream build" (:upstream-build-number result) "finished successfully."))
      (event-bus/unsubscribe ctx ::pipeline-success subscription)
      (clean-up-kill-switch->ch ctx)
      result)))

(defn- last-pipeline-step? [pipeline]
  (let [last-step-id [(count pipeline)]]
    (fn [msg]
      (= last-step-id (:step-id msg)))))

(defn- successful-result? [msg]
  (= :success (get-in msg [:final-result :status])))

(defn- to-pipeline-success-event [msg]
  {:topic   ::pipeline-success
   :payload msg})

(defn- transduced-ch [c xs]
  (let [result (async/chan 1 xs)]
    (async/pipe c result)
    result))

(defn- pipeline-success-ch [[pipeline-id pipeline]]
  (let [subscription        (event-bus/subscribe (:context pipeline) :step-finished)
        pipeline-success-ch (-> subscription
                                (event-bus/only-payload)
                                (transduced-ch (comp
                                                 (filter (last-pipeline-step? pipeline))
                                                 (filter successful-result?)
                                                 (map (fn [msg] {:pipeline-id  pipeline-id
                                                                 :build-number (:build-number msg)
                                                                 :final-result (:final-result msg)}))
                                                 (map to-pipeline-success-event))))]
    pipeline-success-ch))

(defn broadcast-ch! [src-ch out-chs]
  (let [multiple (async/mult src-ch)]
    (doall
      (map (fn [out-ch]
             (async/tap multiple out-ch)) out-chs))))

(defn initialize-value-stream [pipeline-ids-to-pipelines]
  (let [pipelines              (vals pipeline-ids-to-pipelines)
        pipeline-success-chs   (map pipeline-success-ch pipeline-ids-to-pipelines)
        pipeline-success-ch    (async/merge pipeline-success-chs)
        get-event-publisher-ch #(get-in % [:context :event-publisher])
        publisher-chs          (map get-event-publisher-ch pipelines)]
    (broadcast-ch! pipeline-success-ch publisher-chs)))

; TODO: when to unsubscribe?