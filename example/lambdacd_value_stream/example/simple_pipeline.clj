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
            [lambdacd.util :as utils]
            [lambdacd-value-stream.core :as core]
            [clojure.string :as s]
            [lambdacd.steps.support :as support]))

; ================ Some build steps ================

(def lambdacd-repo "git@github.com:flosell/lambdacd")
(def lambdacd-git-repo "git@github.com:flosell/lambdacd-git")

(defn wait-for-lambdacd-commit [args ctx]
  (git/wait-for-git ctx lambdacd-repo
                    :ref "refs/heads/master"
                    :ms-between-polls (* 60 1000)))

(defn clone-lambdacd [args ctx]
  (git/clone ctx lambdacd-repo (:revision args) (:cwd args)))

(defn clone-lambdacd-git [args ctx]
  (git/clone ctx lambdacd-git-repo "refs/heads/master" (:cwd args)))

(defn test-lambdacd [args ctx]
  (shell/bash ctx (:cwd args) "./go test"))

(defn test-lambdacd-git [args ctx]
  (shell/bash ctx (:cwd args) {"LAMBDACD_VERSION" (get-in args [:upstream-result :lambdacd-release-version])}
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
    {:status                   :success
     :lambdacd-release-version (str release-version "-SNAPSHOT")}))

(defn release-lambdacd [args ctx]
  (support/chaining args ctx
                    (shell/bash ctx (:cwd args)
                                "npm install"
                                "./go release-local")
                    (find-release-version args ctx)))


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
       release-lambdacd)))

(def lambdacd-git-pipeline-structure
  `((either
      wait-for-manual-trigger
      ; wait for the other pipeline to be finished:
      wait-for-lambdacd-pipeline)
     (with-workspace
       clone-lambdacd-git
       test-lambdacd-git)))

; ================ Some wiring ================

(defn -main [& args]
  (let [home-dir              (utils/create-temp-dir)
        lambdacd-pipeline     (lambdacd/assemble-pipeline lambdacd-pipeline-structure {:home-dir home-dir})
        lambdacd-git-pipeline (lambdacd/assemble-pipeline lambdacd-git-pipeline-structure {:home-dir (utils/create-temp-dir)})
        lambdacd-ui           (ui/ui-for lambdacd-pipeline)
        lambdacd-git-ui       (ui/ui-for lambdacd-git-pipeline)]
    (git/init-ssh!)
    (core/initialize-value-stream {:lambdacd     lambdacd-pipeline
                                   :lambdacd-git lambdacd-git-pipeline})
    (runners/start-one-run-after-another lambdacd-pipeline)
    ; runners/new-run-after-first-step-finished makes more sense if we want to trigger downstream pipelines,
    ; otherwise we might miss events
    (runners/start-new-run-after-first-step-finished lambdacd-git-pipeline)
    (ring-server/serve (routes
                         (context "/lambdacd" []
                           lambdacd-ui)
                         (context "/lambdacd-git" []
                           lambdacd-git-ui)
                         )
                       {:open-browser? false
                        :port          8083})))