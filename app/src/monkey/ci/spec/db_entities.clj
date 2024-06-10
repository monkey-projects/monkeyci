(ns monkey.ci.spec.db-entities
  "Spec for database entities.  This can be useful to auto-generate database
   record entities for testing, but also to validate entities before persisting 
   them."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [monkey.ci.spec
             [build]
             [common :as c]]))

(def id? int?)

(s/def :db/id id?)
(s/def :db/cuid ::c/cuid)

(s/def :github/secret string?)
(s/def :db/name string?)
(s/def :db/description string?)

(s/def :db/common
  (s/keys :req-un [:db/cuid]
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
              :opt-un [:build/status :db/display-id :build/git])
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

(s/def :db/ssh-key
  (s/keys :req-un [:db/customer-id :ssh/private-key :ssh/public-key :db/description]))

(s/def :label/label string?)
(s/def :label/value string?)

(s/def :db/label-filter
  (s/keys :req-un [:label/label :label/value]))

(s/def :db/label-filter-conjunction (s/coll-of :db/label-filter))
(s/def :db/label-filters (s/coll-of :db/label-filter-conjunction))

(s/def :db/customer-param
  (s/keys :req-un [:db/customer-id]
          :opt-un [:db/label-filters :db/description]))

(s/def :db/params-id id?)

(s/def :db/parameter-value
  (s/keys :req-un [:db/params-id :db/name :label/value]))

(s/def :db/type (s/with-gen
                  string?
                  #(gen/fmap clojure.string/join
                             (gen/vector (gen/char-alphanumeric) 20))))
(s/def :db/type-id string?)
(s/def :db/email string?)

(s/def :db/user
  (-> (s/keys :req-un [:db/type :db/type-id]
              :opt-un [:db/email])
      (s/merge :db/common)))

(s/def :db/user-id id?)
(s/def :join-request-db/status #{"pending" "approved" "rejected"})
(s/def :join-request-db/request-msg string?)
(s/def :join-request-db/response-msg string?)

(s/def :db/join-request
  (-> (s/keys :req-un [:db/user-id :db/customer-id :join-request-db/status]
              :opt-un [:join-request-db/request-msg :join-request-db/response-msg])
      (s/merge :db/common)))
