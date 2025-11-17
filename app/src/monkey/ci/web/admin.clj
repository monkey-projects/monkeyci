(ns monkey.ci.web.admin
  "API Route definitions for administrative purposes.  These are not available
   in the general api and are meant to be used by system administrators or
   system processes only."
  (:require [monkey.ci.common.schemas :as cs]
            [monkey.ci.entities.core :as ec]
            [monkey.ci.web.api
             [admin :as api]
             [mailing :as mailing-api]]
            [monkey.ci.web.common :as c]
            [schema.core :as s]))

(s/defschema UserCredentials
  {:username s/Str
   :password s/Str})

(s/defschema UserCredits
  {:amount s/Int
   (s/optional-key :reason) s/Str
   (s/optional-key :from-time) s/Int})

(s/defschema AutoCredits
  ;; ISO date format
  {:date #"\d{4}-\d{2}-\d{2}"})

(s/defschema CreditSubscription
  {:amount s/Int
   :valid-from s/Int
   (s/optional-key :valid-until) s/Int})

(s/defschema DisableCreditSubscription
  {(s/optional-key :valid-until) s/Int})

(def credits-routes
  ["/credits"
   {:conflicting true}
   [["/issue"
     {:post api/issue-auto-credits
      :parameters {:body AutoCredits}}]
    ["/:org-id"
     {:parameters {:path {:org-id c/Id}}}
     [[""
       ;; TODO Move this under issuances
       {:get api/list-org-credits}]
      ["/issue"
       {:post api/issue-credits
        :parameters {:body UserCredits}}]
      ["/subscription"
       (c/generic-routes
        {:getter api/get-credit-subscription
         :creator api/create-credit-subscription
         :deleter api/cancel-credit-subscription
         :searcher api/list-credit-subscriptions
         :id-key :subscription-id
         :new-schema CreditSubscription
         :delete-schema DisableCreditSubscription})]]]]])

(def mailing-sends-routes
  ["/sends" []])

(def mailing-routes
  ["/mailing"
   (c/generic-routes
    {:id-key :mailing-id
     :new-schema cs/Mailing
     :update-schema cs/Mailing
     :getter mailing-api/get-mailing
     :creator mailing-api/create-mailing
     :updater mailing-api/update-mailing
     :deleter mailing-api/delete-mailing
     :searcher mailing-api/list-mailings
     :child-routes mailing-sends-routes})])

(def admin-routes
  [[""
    ["/login"
     {:post api/login
      :parameters {:body UserCredentials}}]
    ;; TODO Create/delete sysadmin users
    #_["/sysadmin"
       {:post
        {:handler api/create-sysadmin}
        :delete
        {:handler api/delete-sysadmin}}]
    [""
     {:middleware [:sysadmin-check]}
     credits-routes
     mailing-routes
     ["/reaper"
      {:post api/cancel-dangling-builds}]]]])
