(ns monkey.ci.storage.spec
  "Spec for application entities."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [monkey.ci.events.spec :as evt]
            [monkey.ci.spec
             [build :as build]
             [common :as c]
             [gen :as sg]
             [git :as git]
             [invoice :as inv]
             [label :as lbl]
             [mailing :as mailing]
             [script :as script]
             [ssh :as ssh]
             [user :as user]]))

(s/def ::id ::c/cuid)
(s/def :display/id string?)

(s/def :github/secret string?)
(s/def ::name (s/and string? not-empty))
(s/def ::description string?)

(s/def ::common
  (s/keys :req-un [::id]))

(s/def :org/display-id :display/id)

(s/def ::org
  (-> (s/keys :req-un [::name :org/display-id])
      (s/merge ::common)))

(s/def ::lbl/name string?)
(s/def ::lbl/value string?)

(s/def ::label
  (s/keys :req-un [::lbl/name ::lbl/value]))

(s/def ::labels (s/coll-of ::label))

(s/def ::org-id ::c/cuid)
(s/def ::github-id int?)
(s/def ::public boolean?)

(s/def ::repo
  (s/keys :req-un [::org-id ::name :display/id]
          :opt-un [::git/url ::git/main-branch ::github-id ::labels ::public]))

(s/def ::repo-id string?)
(s/def ::idx int?)
(s/def ::credits (s/int-in 0 10000))

;; TODO Merge/replace with ::build/build
(s/def ::build
  (-> (s/keys :req-un [::build/build-id ::org-id ::repo-id ::idx ::build/source]
              :opt-un [::build/status ::script ::git/git
                       ::credits ::build/message #_::webhook-id])
      (s/merge ::c/timed)))

(s/def ::script
  (s/keys :opt-un [::script/script-dir ::jobs]))

(s/def ::jobs (s/map-of :display/id ::job))
(s/def :job/credit-multiplier (s/int-in 0 100))

(s/def ::job
  (-> (s/keys :req-un [::org-id ::repo-id ::build/build-id :display/id]
              :opt-un [:job/status ::labels :job/credit-multiplier])
      (s/merge ::c/timed)))

(s/def ::secret-key string?)
(s/def ::creation-time c/ts?)
(s/def ::last-inv-time c/ts?)

(s/def ::webhook
  (-> (s/keys :req-un [::org-id ::repo-id ::secret-key]
              :opt-un [::creation-time ::last-inv-time])
      (s/merge ::common)))

(s/def ::label-filter
  (s/keys :req-un [::lbl/label ::lbl/value]))
(s/def ::label-filter-conjunction (s/coll-of ::label-filter))

(s/def ::label-filters (s/coll-of ::label-filter-conjunction))

(s/def ::ssh-key
  (-> (s/keys :req-un [::org-id ::ssh/private-key ::ssh/public-key
                       ::description]
              :opt-un [::label-filters])
      (s/merge ::common)))

(s/def ::org-params
  (-> (s/keys :req-un [::org-id ::parameters]
              :opt-un [::description ::label-filters])
      (s/merge ::common)))

(s/def ::parameters (s/coll-of ::parameter-value))

(s/def ::parameter-value
  (s/keys :req-un [::name ::lbl/value]))

(s/def ::orgs (s/coll-of ::id))

(s/def ::user
  (-> (s/keys :req-un [::user/type ::user/type-id]
              :opt-un [::user/email ::orgs])
      (s/merge ::common)))

(s/def ::user-id ::c/cuid)
(s/def :join-request/status #{:pending :approved :rejected})
(s/def :join-request/request-msg string?)
(s/def :join-request/response-msg string?)

(s/def ::join-request
  (-> (s/keys :req-un [::user-id ::org-id :join-request/status]
              :opt-un [:join-request/request-msg :join-request/response-msg])
      (s/merge ::common)))

;; Email registrations are created then an anonymous user registers their email
;; in order to receive mailing updates.
(s/def ::confirmed boolean?)

(s/def ::email-registration
  (-> (s/keys :req-un [::user/email]
              :opt-un [::creation-time ::confirmed])
      (s/merge ::common)))

(s/def ::amount (s/int-in 0 1000000))
(s/def ::valid-from c/ts?)
(s/def ::valid-until c/ts?)
(s/def ::valid-period (s/with-gen string? #(sg/fixed-string 10)))

(s/def ::credit-subscription
  (-> (s/keys :req-un [::org-id ::amount ::valid-from]
              :opt-un [::valid-until ::description ::valid-period])
      (s/merge ::common)))

(s/def ::reason string?)
(s/def ::subscription-id ::c/cuid)

(s/def ::org-credit
  (-> (s/keys :req-un [::org-id ::amount :credit/type]
              :opt-un [::valid-from ::valid-until
                       ::user-id ::subscription-id ::reason])
      (s/merge ::common)))

(s/def ::credit-id ::c/cuid)
(s/def ::consumed-at c/ts?)

(s/def ::credit-consumption
  (-> (s/keys :req-un [::credit-id ::org-id ::repo-id ::build/build-id
                       ::amount ::consumed-at])
      (s/merge ::common)))

(s/def ::webhook-id ::c/cuid)
(s/def ::bitbucket-id string?)

(s/def ::bb-webhook
  (-> (s/keys :req-un [::bitbucket-id ::webhook-id :bb/workspace :bb/repo-slug])
      (s/merge ::common)))

(s/def ::iv (s/with-gen
                    (s/and (partial instance? (class (byte-array 0)))
                           (comp (partial = 16) count))
                    #(sg/fixed-byte-array 16)))

(s/def ::dek string?)

(s/def ::crypto
  (s/keys :req-un [::org-id ::iv ::dek]))

(s/def ::password string?)

(s/def ::sysadmin
  (s/keys :req-un [::user-id ::password]))

(s/def ::invoice
  (-> (s/keys :req-un [::org-id ::inv/kind ::inv/date
                       ::inv/net-amount ::inv/vat-perc ::inv/currency
                       :invoice/details]
              :opt-un [::inv/invoice-nr ::inv/ext-id])
      (s/merge ::common)))

(s/def :invoice/details
  (s/coll-of :invoice/detail))

(s/def :invoice/detail
  (s/keys :req-un [::inv/net-amount ::inv/vat-perc ::description]))

(s/def ::vat-nr string?)

(s/def ::org-invoicing
  (s/keys :req-un [::org-id ::inv/currency ::inv/ext-id ::vat-nr
                   ::inv/address ::inv/country]))

(s/def ::runner keyword?)
(s/def ::details map?)

(s/def ::runner-details
  (s/keys :req-un [::runner ::details]))

;; TODO Remove this, unused
(s/def ::queued-task
  (-> (s/keys :req-un [::creation-time :queued-task/task])
      (s/merge ::common)))

(s/def ::job-id string?)
(s/def ::time c/ts?)
(s/def ::event ::evt/type)

(s/def ::job-event
  (s/keys :req-un [::org-id ::repo-id ::build/build-id ::job-id
                   ::time ::event]
          :opt-un [::details]))

(s/def ::user-token
  (-> (s/keys :req-un [::c/token ::user-id]
              :opt-un [::valid-until ::description])
      (s/merge ::common)))

(s/def ::org-token
  (-> (s/keys :req-un [::c/token ::org-id]
              :opt-un [::valid-until ::description])
      (s/merge ::common)))

(s/def ::mailing
  (-> (s/keys :req-un [::mailing/subject ::creation-time]
              :opt-un [::mailing/text-body ::mailing/html-body])
      (s/merge ::common)))

(s/def ::mailing-id string?)
(s/def ::sent-at c/ts?)
(s/def ::other-dests (s/coll-of string?))

(s/def ::sent-mailing
  (-> (s/keys :req-un [::mailing-id ::sent-at]
              :opt-un [::mailing/to-users ::mailing/to-subscribers ::other-dests])
      (s/merge ::common)))

(s/def ::receive-mailing boolean?)

(s/def ::user-setting
  (s/keys :req-un [::user-id]
          :opt-un [::receive-mailing]))
