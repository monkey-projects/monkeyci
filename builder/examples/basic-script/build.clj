;; Basic Clojure build script
(require '[monkey.ci.build.core :as core])
(require '[monkey.ci.build.shell :as shell])

;; Pipeline with a single step that just prints a message to stdout
(core/pipeline
 {:name "test pipeline"
  :steps [(shell/bash "echo \"Hi, I'm a simple build script!\"")
          (shell/bash "echo" "And I'm another part of that script")]})
