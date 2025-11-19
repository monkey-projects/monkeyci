(ns monkey.ci.spec.entities
  "Spec for application entities."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [monkey.ci.spec
             [build]
             [common :as c]
             [gen :as sg]]))

(def ts? int?)

(s/def :entity/id ::c/cuid)
(s/def :display/id string?)

(s/def :github/secret string?)
(s/def :entity/name (s/and string? not-empty))
(s/def :entity/description string?)

(s/def :entity/common
  (s/keys :req-un [:entity/id]))

;; Maybe we should use instants instead?
(s/def :entity/start-time ts?)
(s/def :entity/end-time ts?)

(s/def :entity/timed
  (s/keys :opt-un [:entity/start-time :entity/end-time]))

(s/def :org/display-id string?)

(s/def :entity/org
  (-> (s/keys :req-un [:entity/name :org/display-id])
      (s/merge :entity/common)))

(s/def :label/name string?)
(s/def :label/value string?)

(s/def :entity/label
  (s/keys :req-un [:label/name :label/value]))

(s/def :entity/labels (s/coll-of :entity/label))

(s/def :entity/org-id ::c/cuid)
(s/def :entity/github-id int?)
(s/def :entity/public boolean?)

(s/def :entity/repo
  (s/keys :req-un [:entity/org-id :entity/name :display/id]
          :opt-un [:git/url :git/main-branch :entity/github-id :entity/labels :entity/public]))

(s/def :entity/repo-id string?)
(s/def :entity/build-id string?)
(s/def :entity/idx int?)
(s/def :entity/credits (s/int-in 0 10000))
(s/def :entity/message string?)

(s/def :entity/build
  (-> (s/keys :req-un [:entity/build-id :entity/org-id :entity/repo-id :entity/idx :build/source]
              :opt-un [:build/status :entity/script :build/git :entity/credits :entity/message])
      (s/merge :entity/timed)))

(s/def :script/script-dir string?)

(s/def :entity/script
  (s/keys :opt-un [:script/script-dir :entity/jobs]))

(s/def :entity/jobs (s/map-of :display/id :entity/job))
(s/def :job/credit-multiplier (s/int-in 0 100))

(s/def :entity/job
  (-> (s/keys :req-un [:entity/org-id :entity/repo-id :entity/build-id :display/id]
              :opt-un [:job/status :entity/labels :job/credit-multiplier])
      (s/merge :entity/timed)))

(s/def :entity/secret-key string?)

(s/def :webhook/creation-time ts?)
(s/def :webhook/last-inv-time ts?)

(s/def :entity/webhook
  (-> (s/keys :req-un [:entity/org-id :entity/repo-id :entity/secret-key]
              :opt-un [:webhook/creation-time :webhook/last-inv-time])
      (s/merge :entity/common)))

(s/def :label-filter/label string?)

(s/def :entity/label-filter
  (s/keys :req-un [:label-filter/label :label/value]))
(s/def :entity/label-filter-conjunction (s/coll-of :entity/label-filter))

(s/def :entity/label-filters (s/coll-of :entity/label-filter-conjunction))

(s/def :ssh/private-key string?)
(s/def :ssh/public-key string?)

(s/def :entity/ssh-key
  (-> (s/keys :req-un [:entity/org-id :ssh/private-key :ssh/public-key
                       :entity/description]
              :opt-un [:entity/label-filters])
      (s/merge :entity/common)))

(s/def :entity/org-params
  (-> (s/keys :req-un [:entity/org-id :entity/parameters]
              :opt-un [:entity/description :entity/label-filters])
      (s/merge :entity/common)))

(s/def :entity/parameters (s/coll-of :entity/parameter-value))

(s/def :entity/parameter-value
  (s/keys :req-un [:entity/name :label/value]))

(s/def :user/type #{:github :bitbucket})
(s/def :user/type-id string?)
(s/def :entity/email string?)
(s/def :user/orgs (s/coll-of :entity/id))

(s/def :entity/user
  (-> (s/keys :req-un [:user/type :user/type-id]
              :opt-un [:entity/email :user/orgs])
      (s/merge :entity/common)))

(s/def :entity/user-id ::c/cuid)
(s/def :join-request/status #{:pending :approved :rejected})
(s/def :join-request/request-msg string?)
(s/def :join-request/response-msg string?)

(s/def :entity/join-request
  (-> (s/keys :req-un [:entity/user-id :entity/org-id :join-request/status]
              :opt-un [:join-request/request-msg :join-request/response-msg])
      (s/merge :entity/common)))

;; Email registrations are created then an anonymous user registers their email
;; in order to receive mailing updates.
(s/def :entity/email-registration
  (-> (s/keys :req-un [:entity/email])
      (s/merge :entity/common)))

(s/def :entity/amount (s/int-in 0 1000000))
(s/def :entity/valid-from ts?)
(s/def :entity/valid-until ts?)

(s/def :entity/credit-subscription
  (-> (s/keys :req-un [:entity/org-id :entity/amount :entity/valid-from]
              :opt-un [:entity/valid-until])
      (s/merge :entity/common)))

(s/def :entity/from-time ts?)
(s/def :entity/reason string?)
(s/def :entity/subscription-id ::c/cuid)

(s/def :entity/org-credit
  (-> (s/keys :req-un [:entity/org-id :entity/amount :credit/type]
              :opt-un [:entity/from-time :entity/user-id :entity/subscription-id :entity/reason])
      (s/merge :entity/common)))

(s/def :entity/credit-id ::c/cuid)
(s/def :entity/consumed-at ts?)

(s/def :entity/credit-consumption
  (-> (s/keys :req-un [:entity/credit-id :entity/org-id :entity/repo-id :entity/build-id
                       :entity/amount :entity/consumed-at])
      (s/merge :entity/common)))

(s/def :entity/webhook-id ::c/cuid)
(s/def :entity/bitbucket-id string?)

(s/def :entity/bb-webhook
  (-> (s/keys :req-un [:entity/bitbucket-id :entity/webhook-id :bb/workspace :bb/repo-slug])
      (s/merge :entity/common)))

(s/def :entity/iv (s/with-gen
                    (s/and (partial instance? (class (byte-array 0)))
                           (comp (partial = 16) count))
                    #(sg/fixed-byte-array 16)))

(s/def :entity/dek string?)

(s/def :entity/crypto
  (s/keys :req-un [:entity/org-id :entity/iv :entity/dek]))

(s/def :entity/password string?)

(s/def :entity/sysadmin
  (s/keys :req-un [:entity/user-id :entity/password]))

(s/def :entity/invoice
  (-> (s/keys :req-un [:entity/org-id :invoice/kind :invoice/invoice-nr :invoice/date
                       :invoice/net-amount :invoice/vat-perc :invoice/currency
                       :invoice/details])
      (s/merge :entity/common)))

(s/def :invoice/details
  (s/coll-of :invoice/detail))

(s/def :invoice/detail
  (s/keys :req-un [:invoice/net-amount :invoice/vat-perc :entity/description]))

(s/def :entity/runner keyword?)
(s/def :runner/details map?)

(s/def :entity/runner-details
  (s/keys :req-un [:entity/runner :runner/details]))

(s/def :entity/queued-task
  (-> (s/keys :req-un [:queued-task/creation-time :queued-task/task])
      (s/merge :entity/common)))

(s/def :entity/job-id string?)
(s/def :job-evt/time ts?)
(s/def :job-evt/event keyword?)
(s/def :job-evt/details map?)

(s/def :entity/job-event
  (s/keys :req-un [:entity/org-id :entity/repo-id :entity/build-id :entity/job-id
                   :job-evt/time :job-evt/event]
          :opt-un [:job-evt/details]))

(s/def :entity/user-token
  (-> (s/keys :req-un [::c/token :entity/user-id]
              :opt-un [:entity/valid-until :entity/description])
      (s/merge :entity/common)))

(s/def :entity/org-token
  (-> (s/keys :req-un [::c/token :entity/org-id]
              :opt-un [:entity/valid-until :entity/description])
      (s/merge :entity/common)))

(s/def :mailing/creation-time ts?)

(s/def :entity/mailing
  (-> (s/keys :req-un [:mailing/subject :mailing/creation-time]
              :opt-un [:mailing/text-body :mailing/html-body])
      (s/merge :entity/common)))

(s/def :entity/mailing-id string?)
(s/def :entity/sent-at ts?)
(s/def :entity/other-dests (s/coll-of string?))

(s/def :entity/sent-mailing
  (-> (s/keys :req-un [:entity/mailing-id :entity/sent-at]
              :opt-un [:mailing/mail-id :mailing/to-users :mailing/to-subscribers :entity/other-dests])
      (s/merge :entity/common)))
