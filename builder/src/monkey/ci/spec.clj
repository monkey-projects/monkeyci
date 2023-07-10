(ns monkey.ci.spec
  (:require [clojure.spec.alpha :as s]))

(s/def :ci/name string?)
(s/def :ci/steps seqable?)
(s/def :ci/pipeline (s/keys :opt-un [:ci/name]
                            :req-un [:ci/steps]))

