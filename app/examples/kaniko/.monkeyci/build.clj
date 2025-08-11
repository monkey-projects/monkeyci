(ns build
  (:require [monkey.ci.api :as m]))

(-> (m/container-job "build-image")
     ;; Use custom image since the kaniko one gives permission errors
    (m/image "docker.io/monkeyci/kaniko:1.21.0") 
    (m/script ["/kaniko/executor --context dir:///home/monkeyci --no-push"]))
