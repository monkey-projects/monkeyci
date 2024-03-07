(ns kaniko.build
  (:require [monkey.ci.build.core :as bc]))

(bc/container-job
 "build-image"
 {:image "gcr.io/kaniko-project/executor:v1.21.0-debug"
  :script ["executor --context dir:///home/monkeyci --no-push"]})
