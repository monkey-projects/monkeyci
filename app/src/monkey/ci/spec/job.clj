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

(s/def ::lifecycle #{:pending :starting :running :stopping :error :failure :success :skipped})
(s/def ::result map?)
(s/def ::output string?)
(s/def ::runner map?)

(s/def ::status
  (s/keys :req-un [::lifecycle ::runner]
          :opt-un [::result ::output]))

(s/def ::job
  (s/keys :req-un [::id ::type]
          :opt-un [::save-artifacts ::restore-artifacts ::caches
                   ::dependencies ::status]))
