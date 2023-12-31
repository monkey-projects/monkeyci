(ns monkey.ci.build.spec
  (:require [clojure.spec.alpha :as s]))

(s/def :ci/name string?)
(s/def :ci/action fn?)
(s/def :ci/status #{:success :failure :skipped})
(s/def :ci/script-step string?)
(s/def :ci/script (s/coll-of :ci/script-step))

(s/def :container/image string?)
(s/def :container/entrypoint (s/coll-of string?))
(s/def :container/cmd (s/coll-of string?))
(s/def :container/mount (s/coll-of string? :count 2))
(s/def :container/mounts (s/coll-of :container/mount))
(s/def :container/env (s/map-of string? string?))
(s/def :container/platform string?)

(s/def :ci/step (s/or :fn fn?
                      :action
                      (s/keys :req-un [:ci/action]
                              :opt-un [:ci/name])
                      :container
                      (s/keys :req [:container/image]
                              :opt [:container/entrypoint :container/cmd :container/mounts :container/env
                                    :container/platform]
                              :opt-un [:ci/script :ci/name])))
(s/def :ci/output string?)
(s/def :ci/exception (partial instance? java.lang.Exception))

(s/def :ci/step-result (s/keys :req-un [:ci/status]
                               :opt-un [:ci/output :ci/exception]))
(s/def :ci/last-result :ci/step-result)

(s/def :ci/steps (s/coll-of :ci/step))
(s/def :ci/pipeline (s/keys :req-un [:ci/steps]
                            :opt-un [:ci/name]))
(s/def :ci/env map?)

;; The run context
(s/def :ci/context (s/keys :opt-un [:ci/status :ci/last-result]
                           :req-un [:ci/pipeline :ci/step :ci/env]))
