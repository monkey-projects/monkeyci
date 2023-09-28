;; Basic build script that uses Docker
(require '[monkey.ci.build.core :as core])
(require '[monkey.ci.build.container :as container])
(require '[monkey.ci.build.shell :as shell])

;; Should run using a step runner that supports docker images
(core/pipeline
 {:name "test pipeline"
  :steps [{:container/image "debian:latest"
           :script ["echo" "I'm running from Debian"]
           :action (constantly nil)}
          {:container/image "alpine:latest"
           :script ["echo" "And I'm running from Alpine"]
           :action (constantly nil)}]})
