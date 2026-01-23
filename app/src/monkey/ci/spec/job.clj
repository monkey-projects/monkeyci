(ns monkey.ci.spec.job
  "Spec definitions for build jobs"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(s/def ::type #{:action :container})
(s/def ::id c/id?)
(s/def ::path c/path?)
(s/def ::dependencies (s/coll-of ::id))

(s/def ::path-ref
  (s/keys :req-un [::id ::path]))

(s/def ::save-artifacts (s/coll-of ::path-ref))
(s/def ::restore-artifacts (s/coll-of ::path-ref))
(s/def ::caches (s/coll-of ::path-ref))

(s/def ::lifecycle
  #{:pending     ; Job is pending execution
    :starting    ; Job is being started
    :running     ; Job is executing
    :stopping    ; Job has finished execution
    :error       ; An unexpected error occurred
    :failure     ; Job failed to execute successfully
    :success     ; Job succeeded
    :skipped     ; Job was not executed (preconditions not satisfied or build canceled)
    :blocked     ; Job is blocked and waits for approval
    })

(s/def ::result map?)
(s/def ::output string?)
(s/def ::runner map?)
(s/def ::blocked boolean?)

(s/def ::status
  (s/keys :req-un [::lifecycle ::runner]
          :opt-un [::result ::output]))

(s/def ::common-spec
  (s/keys :opt-un [::type ::save-artifacts ::restore-artifacts ::caches ::blocked ::dependencies]))

(defmulti job-spec :type)

(s/def ::action fn?)

(defmethod job-spec :action [_]
  (-> (s/keys :req-un [::action])
      (s/merge ::common-spec)))

(s/def ::image string?)

(defmethod job-spec :container [_]
  (-> (s/keys :req-un [::image])
      (s/merge ::common-spec)))

(s/def ::spec (s/multi-spec job-spec :type))

(s/def ::job
  (s/keys :req-un [::id ::spec]
          :opt-un [::status]))
