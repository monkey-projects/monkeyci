(ns monkey.ci.web.admin
  "API Route definitions for administrative purposes.  These are not available
   in the general api and are meant to be used by system administrators or
   system processes only."
  (:require [monkey.ci.entities.core :as ec]
            [monkey.ci.web.api.admin :as api]
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
    ["/:customer-id"
     {:parameters {:path {:customer-id c/Id}}}
     [[""
       ;; TODO Move this under issuances
       {:get api/list-customer-credits}]
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
     ["/reaper"
      {:post api/cancel-dangling-builds}]]]])
