(ns kaniko.build
  (:require [monkey.ci.build.core :as bc]))

(bc/container-job
 "build-image"
 ;; Use custom image since the kaniko one gives permission errors
 {:image "docker.io/monkeyci/kaniko:1.21.0"
  :script ["/kaniko/executor --context dir:///home/monkeyci --no-push"]})
