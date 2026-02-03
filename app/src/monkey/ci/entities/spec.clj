(ns monkey.ci.entities.spec
  "Spec for database entities.  This can be useful to auto-generate database
   record entities for testing, but also to validate entities before persisting 
   them."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [monkey.ci.spec
             [build]
             [common :as c]
             [gen :as sg]
             [label :as lbl]]))

(def id? int?)
(def ts? int?)

(s/def ::id id?)
(s/def ::cuid ::c/cuid)

(s/def :github/secret string?)
(s/def ::name (s/and string? not-empty))
(s/def ::description string?)
(s/def ::display-id (s/and string? not-empty))

(s/def ::common
  (s/keys :req-un [::cuid]
          :opt-un [::id]))

;; Maybe we should use instants instead?
(s/def ::start-time ts?)
(s/def ::end-time ts?)

(s/def ::timed
  (-> (s/keys :opt-un [::start-time ::end-time])
      (s/merge ::common)))

(s/def ::org
  (-> (s/keys :req-un [::name ::display-id])
      (s/merge ::common)))

(s/def ::org-id id?)
(s/def ::public boolean?)

(s/def ::repo
  (-> (s/keys :req-un [::display-id ::org-id ::name]
              :opt-un [:git/url :git/main-branch ::public])
      (s/merge ::common)))

(s/def ::repo-id id?)
(s/def ::idx int?)
(s/def ::credits (s/int-in 0 10000))
(s/def ::message string?)

(s/def ::build
  (-> (s/keys :req-un [::repo-id ::idx]
              :opt-un [:build/status ::display-id :build/git ::credits ::message])
      (s/merge ::timed)))

(s/def :job/details map?)

(s/def ::build-id id?)
(s/def ::credit-multiplier (s/int-in 0 10))

(s/def ::job
  (-> (s/keys :req-un [::build-id ::display-id :job/details]
              :opt-un [:job/status ::credit-multiplier])
      (s/merge ::timed)))

(s/def ::creation-time ts?)
(s/def ::last-inv-time ts?)

(s/def ::webhook
  (-> (s/keys :req-un [::repo-id :github/secret]
              :opt-un [::creation-time ::last-inv-time])
      (s/merge ::common)))

(s/def ::ssh-key
  (s/keys :req-un [::org-id :ssh/private-key :ssh/public-key ::description]))

(s/def ::label-filter
  (s/keys :req-un [::lbl/label ::lbl/value]))

(s/def ::label-filter-conjunction (s/coll-of ::label-filter))
(s/def ::label-filters (s/coll-of ::label-filter-conjunction))

(s/def ::org-param
  (s/keys :req-un [::org-id]
          :opt-un [::label-filters ::description]))

(s/def ::params-id id?)

(s/def ::parameter-value
  (s/keys :req-un [::params-id ::lbl/name ::lbl/value]))

(s/def ::type (s/with-gen
                  string?
                  #(sg/fixed-string 20)))
(s/def ::type-id string?)
(s/def ::email string?)

(s/def ::user
  (-> (s/keys :req-un [::type ::type-id]
              :opt-un [::email])
      (s/merge ::common)))

(s/def ::user-id id?)
(s/def :join-request-db/status #{"pending" "approved" "rejected"})
(s/def :join-request-db/request-msg string?)
(s/def :join-request-db/response-msg string?)

(s/def ::join-request
  (-> (s/keys :req-un [::user-id ::org-id :join-request-db/status]
              :opt-un [:join-request-db/request-msg :join-request-db/response-msg])
      (s/merge ::common)))

(s/def ::confirmed boolean?)

(s/def ::email-registration
  (-> (s/keys :req-un [::email]
              :opt-un [::creation-time ::confirmed])
      (s/merge ::common)))

;;; Credits

(s/def ::valid-from ts?)
(s/def ::valid-until ts?)
(s/def ::valid-period (s/with-gen string? #(sg/fixed-string 10)))
(s/def ::amount (s/int-in 0 1000000))

(s/def ::credit-subscription
  (-> (s/keys :req-un [::org-id ::amount ::valid-from]
              :opt-un [::valid-until ::description ::valid-period])
      (s/merge ::common)))

(s/def ::subscription-id int?)
(s/def ::reason string?)

(s/def ::org-credit
  (-> (s/keys :req-un [::org-id ::amount :credit/type]
              :opt-un [::valid-from ::valid-until ::user-id ::subscription-id ::reason])
      (s/merge ::common)))

(s/def ::credit-id int?)
(s/def ::consumed-at ts?)

(s/def ::credit-consumption
  (-> (s/keys :req-un [::credit-id ::build-id ::consumed-at ::amount])
      (s/merge ::common)))

(s/def ::webhook-id int?)
(s/def ::bitbucket-id string?)

(s/def ::bb-webhook
  (-> (s/keys :req-un [::bitbucket-id ::webhook-id :bb/workspace :bb/repo-slug])
      (s/merge ::common)))

(s/def ::iv (s/with-gen
                (s/and (partial instance? (class (byte-array 0)))
                       (comp (partial = 16) count))
                #(sg/fixed-byte-array 16)))

;; Data encryption key
(s/def ::dek string?)

(s/def ::crypto
  (s/keys :req-un [::org-id ::iv ::dek]))

(s/def ::password string?)

(s/def ::sysadmin
  (s/keys :req-un [::user-id ::password]))

(s/def ::invoice
  (-> (s/keys :req-un [::org-id :invoice/kind :invoice/date
                       :invoice/currency :invoice/net-amount :invoice/vat-perc
                       :db-invoice/details]
              :opt-un [:invoice/invoice-nr :invoice/ext-id])
      (s/merge ::common)))

(s/def :db-invoice/details
  (s/coll-of :db-invoice/detail))

(s/def :db-invoice/detail
  (s/keys :req-un [:invoice/net-amount :invoice/vat-perc ::description]))

(s/def ::vat-nr string?)

(s/def ::org-invoicing
  (s/keys :req-un [::org-id :invoice/currency :invoice/address :invoice/country]
          :opt-un [::vat-nr :invoice/ext-id]))

(s/def ::runner keyword?)
(s/def :runner/details map?)

(s/def ::runner-details
  (s/keys :req-un [::runner :runner/details]))

(s/def ::queued-task
  (-> (s/keys :req-un [:queued-task/creation-time :queued-task/task])
      (s/merge ::common)))

(s/def ::job-id id?)
(s/def :job-evt/time ts?)
(s/def :job-evt/event keyword?)
(s/def :job-evt/details map?)

(s/def ::job-event
  (s/keys :req-un [::job-id :job-evt/time :job-evt/event]
          :opt-un [:job-evt/details]))

(s/def ::user-token
  (-> (s/keys :req-un [::c/token ::user-id]
              :opt-un [::valid-until ::description])
      (s/merge ::common)))

(s/def ::org-token
  (-> (s/keys :req-un [::c/token ::org-id]
              :opt-un [::valid-until ::description])
      (s/merge ::common)))

(s/def :mailing/creation-time ts?)

(s/def ::mailing
  (-> (s/keys :req-un [:mailing/subject :mailing/creation-time]
              :opt-un [:mailing/text-body :mailing/html-body])
      (s/merge ::common)))

(s/def ::mailing-id id?)
(s/def ::sent-at ts?)
(s/def ::other-dests string?)

(s/def ::sent-mailing
  (-> (s/keys :req-un [::mailing-id ::sent-at]
              :opt-un [:mailing/to-users :mailing/to-subscribers ::other-dests])
      (s/merge ::common)))

(s/def ::receive-mailing boolean?)

(s/def ::user-setting
  (-> (s/keys :req-un [::user-id]
              :opt-un [::receive-mailing])))
