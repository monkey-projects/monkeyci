(ns monkey.ci.spec.entities
  "Spec for database entities.  This can be useful to auto-generate database
   record entities for testing, but also to validate entities before persisting 
   them."
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec
             [build]
             [common :as c]]))

(def id? int?)

(s/def :entity/id id?)
(s/def :entity/uuid uuid?)

(s/def :github/secret string?)
(s/def :entity/name string?)

(s/def :entity/common
  (s/keys :req-un [:entity/id :entity/uuid]))

;; Maybe we should use instants instead?
(s/def :entity/start-time int?)
(s/def :entity/end-time int?)

(s/def :entity/timed
  (-> (s/keys :opt-un [:entity/start-time :entity/end-time])
      (s/merge :entity/common)))

(s/def :entity/customer
  (-> (s/keys :req-un [:entity/name])
      (s/merge :entity/common)))

(s/def :entity/display-id string?)
(s/def :entity/customer-id id?)

(s/def :entity/repo
  (-> (s/keys :req-un [:entity/display-id :entity/customer-id :entity/name]
              :opt-un [:git/url :git/main-branch])
      (s/merge :entity/common)))

(s/def :entity/repo-id id?)
(s/def :entity/idx int?)

(s/def :entity/build
  (-> (s/keys :req-un [:entity/repo-id :entity/idx]
              :opt-un [:build/status])
      (s/merge :entity/timed)))

(s/def :job/details map?)

(s/def :entity/build-id id?)

(s/def :entity/job
  (-> (s/keys :req-un [:entity/build-id :entity/display-id :job/details]
              :opt-un [:job/status])
      (s/merge :entity/timed)))

(s/def :entity/webhook
  (-> (s/keys :req-un [:build/repo-id :github/secret])
      (s/merge :entity/common)))
