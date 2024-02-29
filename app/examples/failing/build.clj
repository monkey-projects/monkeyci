(require '[monkey.ci.build.core :as bc])

(bc/pipeline
 {:name "Failing pipeline"
  :jobs [(bc/action-job "failing-job" (constantly bc/failure))]})

