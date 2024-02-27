(ns monkey.ci.build.spec
  (:require [clojure.spec.alpha :as s]))

(s/def :ci/name string?)
(s/def :ci/action fn?)
(s/def :ci/status #{:success :failure :skipped :canceled :running})
(s/def :ci/script-step string?)
(s/def :ci/script (s/coll-of :ci/script-step))

(s/def :cache/id string?)
(s/def :cache/path string?)
(s/def :ci/cache-config (s/keys :req-un [:cache/id :cache/path]))
(s/def :ci/caches (s/coll-of :ci/cache-config))

(s/def :artifact/id string?)
(s/def :artifact/path string?)
(s/def :ci/artifact-config (s/keys :req-un [:artifact/id :artifact/path]))
(s/def :ci/artifacts (s/coll-of :ci/artifact-config))

(s/def :ci/save-artifacts (s/merge :ci/artifacts))
(s/def :ci/restore-artifacts (s/merge :ci/artifacts))

(s/def :container/image string?)
(s/def :container/entrypoint (s/coll-of string?))
(s/def :container/cmd (s/coll-of string?))
(s/def :container/mount (s/coll-of string? :count 2))
(s/def :container/mounts (s/coll-of :container/mount))
(s/def :container/env (s/map-of string? string?))
(s/def :container/platform string?)

(s/def :ci/basic-job (s/keys :opt-un [:ci/name :ci/caches :ci/restore-artifacts :ci/save-artifacts]))

(s/def :ci/job (s/or :fn fn?
                     :action
                     (-> (s/keys :req-un [:ci/action])
                         (s/merge :ci/basic-job))
                     :container
                     (-> (s/keys :req [:container/image]
                                 :opt [:container/entrypoint :container/cmd :container/mounts :container/env
                                       :container/platform]
                                 :opt-un [:ci/script])
                         (s/merge :ci/basic-job))))

(s/def :ci/output string?)
(s/def :ci/exception (partial instance? java.lang.Exception))

(s/def :ci/job-result (s/keys :req-un [:ci/status]
                               :opt-un [:ci/output :ci/exception]))
(s/def :ci/last-result :ci/job-result)

(s/def :ci/jobs (s/coll-of :ci/job))
;; For backwards compatibility
(s/def :ci/steps :ci/jobs)
(s/def :ci/pipeline (s/keys :opt-un [:ci/name :ci/jobs :ci/steps]))
(s/def :ci/env map?)

;; The run context
(s/def :ci/context (s/keys :opt-un [:ci/status :ci/last-result]
                           :req-un [:ci/pipeline :ci/job :ci/env]))
