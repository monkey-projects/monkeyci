(ns monkey.ci.spec.db-entities
  "Spec for database entities.  This can be useful to auto-generate database
   record entities for testing, but also to validate entities before persisting 
   them."
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec
             [build]
             [common :as c]]))

(def id? int?)

(s/def :db/id id?)
(s/def :db/uuid uuid?)

(s/def :github/secret string?)
(s/def :db/name string?)

(s/def :db/common
  (s/keys :req-un [:db/uuid]
          :opt-un [:db/id]))

;; Maybe we should use instants instead?
(s/def :db/start-time int?)
(s/def :db/end-time int?)

(s/def :db/timed
  (-> (s/keys :opt-un [:db/start-time :db/end-time])
      (s/merge :db/common)))

(s/def :db/customer
  (-> (s/keys :req-un [:db/name])
      (s/merge :db/common)))

(s/def :db/display-id string?)
(s/def :db/customer-id id?)

(s/def :db/repo
  (-> (s/keys :req-un [:db/display-id :db/customer-id :db/name]
              :opt-un [:git/url :git/main-branch])
      (s/merge :db/common)))

(s/def :db/repo-id id?)
(s/def :db/idx int?)

(s/def :db/build
  (-> (s/keys :req-un [:db/repo-id :db/idx]
              :opt-un [:build/status])
      (s/merge :db/timed)))

(s/def :job/details map?)

(s/def :db/build-id id?)

(s/def :db/job
  (-> (s/keys :req-un [:db/build-id :db/display-id :job/details]
              :opt-un [:job/status])
      (s/merge :db/timed)))

(s/def :db/webhook
  (-> (s/keys :req-un [:build/repo-id :github/secret])
      (s/merge :db/common)))
