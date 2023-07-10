;; Basic build script that uses Docker
(require '[monkey.ci.build.core :as core])
(require '[monkey.ci.build.container :as container])
(require '[monkey.ci.build.shell :as shell])

;; Should run using a step runner that supports docker images
(core/pipeline
 {:name "test pipeline"
  ;; Run this pipeline in containers, with default image
  :runner (container/runner {:image "debian:latest"})
  :steps [(-> (shell/bash "echo" "I'm running from Debian")
              (container/image "debian"))
          (-> (shell/bash "echo" "And I'm running from Alpine")
              (container/image "alpine"))]})
