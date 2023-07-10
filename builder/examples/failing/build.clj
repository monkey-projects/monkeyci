(require '[monkey.ci.build.core :as bc])

(bc/pipeline
 {:name "Failing pipeline"
  :steps [(constantly bc/failure)]})

