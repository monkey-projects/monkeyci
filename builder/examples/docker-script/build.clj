;; Basic build script that uses Docker
(require '[monkey.ci.build.core :as core])
(require '[monkey.ci.build.docker :as docker])
(require '[monkey.ci.build.shell :as shell])

;; Pipeline with a single step that just prints a message to stdout
(core/pipeline
 {:name "test pipeline"
  ;; Run this pipeline in Docker, with default image
  :runner (docker/runner {:image "debian:latest"})
  :steps [(shell/bash "echo" "I'm running from Debian")
          (-> (shell/bash "echo" "And I'm running from Alpine")
              (docker/image "alpine"))]})
