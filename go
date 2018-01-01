#!/bin/bash

set -e

goal_test() {
  lein test
}

goal_run() {
  NAMESPACE="lambdacd-value-stream.example.simple-pipeline"
  lein run -m ${NAMESPACE}
}

goal_push() {
  goal_test && git push
}
goal_release() {
  goal_test && lein release && scripts/github-release.sh
}

if type -t "goal_$1" &>/dev/null; then
  goal_$1 ${@:2}
else
  echo "usage: $0 <goal>
goal:
    test      -- run tests
    push      -- run all tests and push current state
    release   -- release current version
    run       -- run example pipeline"
  exit 1
fi
