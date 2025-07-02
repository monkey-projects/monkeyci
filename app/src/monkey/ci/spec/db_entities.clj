(ns monkey.ci.spec.db-entities
  "Spec for database entities.  This can be useful to auto-generate database
   record entities for testing, but also to validate entities before persisting 
   them."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [monkey.ci.spec
             [build]
             [common :as c]
             [gen :as sg]]))

(def id? int?)
(def ts? int?)

(s/def :db/id id?)
(s/def :db/cuid ::c/cuid)

(s/def :github/secret string?)
(s/def :db/name (s/and string? not-empty))
(s/def :db/description string?)

(s/def :db/common
  (s/keys :req-un [:db/cuid]
          :opt-un [:db/id]))

;; Maybe we should use instants instead?
(s/def :db/start-time ts?)
(s/def :db/end-time ts?)

(s/def :db/timed
  (-> (s/keys :opt-un [:db/start-time :db/end-time])
      (s/merge :db/common)))

(s/def :db/org
  (-> (s/keys :req-un [:db/name])
      (s/merge :db/common)))

(s/def :db/display-id string?)
(s/def :db/org-id id?)

(s/def :db/repo
  (-> (s/keys :req-un [:db/display-id :db/org-id :db/name]
              :opt-un [:git/url :git/main-branch])
      (s/merge :db/common)))

(s/def :db/repo-id id?)
(s/def :db/idx int?)
(s/def :db/credits (s/int-in 0 10000))
(s/def :db/message string?)

(s/def :db/build
  (-> (s/keys :req-un [:db/repo-id :db/idx]
              :opt-un [:build/status :db/display-id :build/git :db/credits :db/message])
      (s/merge :db/timed)))

(s/def :job/details map?)

(s/def :db/build-id id?)
(s/def :db/credit-multiplier (s/int-in 0 10))

(s/def :db/job
  (-> (s/keys :req-un [:db/build-id :db/display-id :job/details]
              :opt-un [:job/status :db/credit-multiplier])
      (s/merge :db/timed)))

(s/def :db/webhook
  (-> (s/keys :req-un [:build/repo-id :github/secret])
      (s/merge :db/common)))

(s/def :db/ssh-key
  (s/keys :req-un [:db/org-id :ssh/private-key :ssh/public-key :db/description]))

(s/def :label/label string?)
(s/def :label/value string?)

(s/def :db/label-filter
  (s/keys :req-un [:label/label :label/value]))

(s/def :db/label-filter-conjunction (s/coll-of :db/label-filter))
(s/def :db/label-filters (s/coll-of :db/label-filter-conjunction))

(s/def :db/org-param
  (s/keys :req-un [:db/org-id]
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
  (-> (s/keys :req-un [:db/user-id :db/org-id :join-request-db/status]
              :opt-un [:join-request-db/request-msg :join-request-db/response-msg])
      (s/merge :db/common)))

(s/def :db/email-registration
  (-> (s/keys :req-un [:db/email])
      (s/merge :db/common)))

;;; Credits

(s/def :db/from-time ts?)
(s/def :db/valid-from ts?)
(s/def :db/valid-until ts?)
(s/def :db/amount (s/int-in 0 1000000))

(s/def :db/credit-subscription
  (-> (s/keys :req-un [:db/org-id :db/amount :db/valid-from]
              :opt-un [:db/valid-until])
      (s/merge :db/common)))

(s/def :db/subscription-id int?)
(s/def :db/reason string?)

(s/def :db/org-credit
  (-> (s/keys :req-un [:db/org-id :db/amount :credit/type]
              :opt-un [:db/from-time :db/user-id :db/subscription-id :db/reason])
      (s/merge :db/common)))

(s/def :db/credit-id int?)
(s/def :db/consumed-at ts?)

(s/def :db/credit-consumption
  (-> (s/keys :req-un [:db/credit-id :db/build-id :db/consumed-at :db/amount])
      (s/merge :db/common)))

(s/def :db/webhook-id int?)
(s/def :db/bitbucket-id string?)

(s/def :db/bb-webhook
  (-> (s/keys :req-un [:db/bitbucket-id :db/webhook-id :bb/workspace :bb/repo-slug])
      (s/merge :db/common)))

(s/def :db/iv (s/with-gen
                (s/and (partial instance? (class (byte-array 0)))
                       (comp (partial = 16) count))
                #(sg/fixed-byte-array 16)))

;; Data encryption key
(s/def :db/dek string?)

(s/def :db/crypto
  (s/keys :req-un [:db/org-id :db/iv :db/dek]))

(s/def :db/password string?)

(s/def :db/sysadmin
  (s/keys :req-un [:db/user-id :db/password]))

(s/def :db/invoice
  (-> (s/keys :req-un [:db/org-id :invoice/kind :invoice/invoice-nr :invoice/date
                       :invoice/currency :invoice/net-amount :invoice/vat-perc
                       :db-invoice/details])
      (s/merge :db/common)))

(s/def :db-invoice/details
  (s/coll-of :db-invoice/detail))

(s/def :db-invoice/detail
  (s/keys :req-un [:invoice/net-amount :invoice/vat-perc :db/description]))

(s/def :db/runner keyword?)
(s/def :runner/details map?)

(s/def :db/runner-details
  (s/keys :req-un [:db/runner :runner/details]))

(s/def :db/queued-task
  (-> (s/keys :req-un [:queued-task/creation-time :queued-task/task])
      (s/merge :db/common)))

(s/def :db/job-id id?)
(s/def :job-evt/time ts?)
(s/def :job-evt/event keyword?)
(s/def :job-evt/details map?)

(s/def :db/job-event
  (s/keys :req-un [:db/job-id :job-evt/time :job-evt/event]
          :opt-un [:job-evt/details]))
