(ns monkey.ci.spec.job.common
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(s/def ::schema #{:v1 :v2})
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

(s/def ::status ::lifecycle)

(s/def ::result map?)
(s/def ::output string?)
(s/def ::runner map?)
(s/def ::blocked boolean?)
(s/def ::credit-multiplier (s/int-in 0 100))

(s/def ::action fn?)

(s/def ::image string?)
(s/def ::size int?)
(s/def ::arch #{:arm :amd})
(s/def ::command string?)
(s/def ::script (s/coll-of ::command))
(s/def ::env (s/map-of string? string?))
