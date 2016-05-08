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
            [lambdacd.util :as utils]))

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
  (shell/bash ctx (:cwd args) "./go test"))

(defn release-lambdacd [args ctx]
  (shell/bash ctx (:cwd args) "./go release-local"))

(defn wait-for-lambdacd-pipeline [args ctx]
  (value-stream/wait-for-pipline-success :lambdacd ctx))

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
      wait-for-lambdacd-pipeline)
     (with-workspace
       clone-lambdacd-git
       test-lambdacd-git)))

(defn -main [& args]
  (let [home-dir              (utils/create-temp-dir)
        lambdacd-pipeline     (lambdacd/assemble-pipeline lambdacd-pipeline-structure {:home-dir home-dir})
        lambdacd-git-pipeline (lambdacd/assemble-pipeline lambdacd-git-pipeline-structure {:home-dir (utils/create-temp-dir)})
        lambdacd-ui           (ui/ui-for lambdacd-pipeline)
        lambdacd-git-ui       (ui/ui-for lambdacd-git-pipeline)
        ]
    (git/init-ssh!)
    (runners/start-one-run-after-another lambdacd-pipeline)
    (runners/start-one-run-after-another lambdacd-git-pipeline)
    (ring-server/serve (routes
                         (context "/lambdacd" []
                           lambdacd-ui)
                         (context "/lambdacd-git" []
                           lambdacd-git-ui)
                         )
                       {:open-browser? false
                        :port          8083})))