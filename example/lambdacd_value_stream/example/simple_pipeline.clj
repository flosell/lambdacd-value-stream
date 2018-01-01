(ns lambdacd-value-stream.example.simple-pipeline
  (:use [compojure.core])
  (:require [lambdacd.steps.shell :as shell]
            [lambdacd.steps.manualtrigger :refer [wait-for-manual-trigger]]
            [lambdacd.steps.control-flow :refer [either with-workspace in-parallel run]]
            [lambdacd.core :as lambdacd]
            [lambdacd-value-stream.core :as value-stream]
            [ring.server.standalone :as ring-server]
            [lambdacd.ui.ui-server :as ui]
            [lambdacd-git.core :as git]
            [lambdacd.runners :as runners]
            [clojure.java.io :as io]
            [lambdacd-value-stream.test_utils :as utils]
            [lambdacd-value-stream.core :as core]
            [clojure.string :as s]
            [lambdacd.steps.support :as support]))

; ================ Some build steps ================

(def lambdacd-repo "git@github.com:flosell/lambdacd")
(def lambdacd-git-repo "git@github.com:flosell/lambdacd-git")
(def lambdacd-cctray-repo "git@github.com:flosell/lambdacd-cctray")

(defn wait-for-lambdacd-commit [args ctx]
  (git/wait-for-git ctx lambdacd-repo
                    :ref "refs/heads/master"
                    :ms-between-polls (* 60 1000)))

(defn clone-lambdacd [args ctx]
  (git/clone ctx lambdacd-repo (:revision args) (:cwd args)))

(defn clone-lambdacd-git [args ctx]
  (git/clone ctx lambdacd-git-repo "refs/heads/master" (:cwd args)))

(defn clone-lambdacd-cctray [args ctx]
  (git/clone ctx lambdacd-cctray-repo "refs/heads/master" (:cwd args)))

(defn test-lambdacd [args ctx]
  (shell/bash ctx (:cwd args) "./go test"))

(defn test-lambdacd-git [args ctx]
  (shell/bash ctx (:cwd args) {"LAMBDACD_VERSION" (get-in args [:upstream-result :global :lambdacd-release-version])}
              "./go test"))

(defn test-lambdacd-cctray [args ctx]
  (shell/bash ctx (:cwd args) {"LAMBDACD_VERSION" (get-in args [:upstream-args :global :lambdacd-release-version])}
              "./go test"))

(defn find-release-version [args ctx]
  (let [cwd             (:cwd args)
        file            (->> (io/file cwd "target")
                             (file-seq)
                             (filter #(.contains (.getName %) "-SNAPSHOT.jar"))
                             (first))
        release-version (-> file
                            (.getName)
                            (s/split #"-")
                            (second))]
    {:status :success
     :global {:lambdacd-release-version (str release-version "-SNAPSHOT")}}))

(defn release-lambdacd [args ctx]
  (support/chaining args ctx
                    (shell/bash ctx (:cwd args)
                                "npm install"
                                "./go release-local")
                    (find-release-version args ctx)))

(defn trigger-downstream [args ctx]
  (value-stream/trigger-downstream-pipeline :lambdacd-cctray args ctx))


(defn wait-for-lambdacd-pipeline [args ctx]
  (value-stream/wait-for-pipline-success :lambdacd ctx))

; ================ Two Pipelines ================

(def lambdacd-pipeline-structure
  `((either
      wait-for-manual-trigger
      wait-for-lambdacd-commit)
     (with-workspace
       clone-lambdacd
       test-lambdacd
       release-lambdacd
       trigger-downstream)))

(def lambdacd-git-pipeline-structure
  `((either
      wait-for-manual-trigger
      ; wait for the other pipeline to be finished:
      wait-for-lambdacd-pipeline)
     (with-workspace
       clone-lambdacd-git
       test-lambdacd-git)))

(def lambdacd-cctray-pipeline-structure
  `((either
      wait-for-manual-trigger
      ; wait for other pipeline to trigger this one
      value-stream/wait-for-upstream-trigger)
     (with-workspace
       clone-lambdacd-cctray
       test-lambdacd-cctray)))

; ================ Some wiring ================

(defn -main [& args]
  (let [home-dir                 (utils/create-temp-dir)
        lambdacd-pipeline        (lambdacd/assemble-pipeline lambdacd-pipeline-structure {:home-dir     home-dir
                                                                                          :value-stream {:pipeline-id :lambdacd}})
        lambdacd-git-pipeline    (lambdacd/assemble-pipeline lambdacd-git-pipeline-structure {:home-dir     (utils/create-temp-dir)
                                                                                              :value-stream {:pipeline-id :lambdacd-git}})
        lambdacd-cctray-pipeline (lambdacd/assemble-pipeline lambdacd-cctray-pipeline-structure {:home-dir     (utils/create-temp-dir)
                                                                                                 :value-stream {:pipeline-id :lambdacd-cctray}})
        lambdacd-ui              (ui/ui-for lambdacd-pipeline)
        lambdacd-git-ui          (ui/ui-for lambdacd-git-pipeline)
        lambdacd-cctray-ui       (ui/ui-for lambdacd-cctray-pipeline)]
    (git/init-ssh!)
    (core/initialize-value-stream [lambdacd-pipeline lambdacd-git-pipeline lambdacd-cctray-pipeline])
    (runners/start-one-run-after-another lambdacd-pipeline)
    ; runners/new-run-after-first-step-finished makes more sense if we want to trigger downstream pipelines,
    ; otherwise we might miss events
    (runners/start-new-run-after-first-step-finished lambdacd-git-pipeline)
    (runners/start-new-run-after-first-step-finished lambdacd-cctray-pipeline)
    (ring-server/serve (routes
                         (context "/lambdacd" []
                           lambdacd-ui)
                         (context "/lambdacd-git" []
                           lambdacd-git-ui)
                         (context "/lambdacd-cctray" [] lambdacd-cctray-ui))
                       {:open-browser? false
                        :port          8083})))
