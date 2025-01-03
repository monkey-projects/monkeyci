(ns monkey.ci.web.admin
  "API Route definitions for administrative purposes.  These are not available
   in the general api and are meant to be used by system administrators or
   system processes only."
  (:require [monkey.ci.web.api.admin :as api]
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
  {:from-time s/Int})

(def credits-routes
  ["/credits"
   {:conflicting true}
   [["/issue"
     {:post api/issue-auto-credits
      :parameters {:body AutoCredits}}]
    ["/:customer-id"
     {:parameters {:path {:customer-id c/Id}}}
     [[""
       {:get api/list-customer-credits}]
      ["/issue"
       {:post api/issue-credits
        :parameters {:body UserCredits}}]]]]])

(def admin-routes
  [[""
    ["/login"
     {:post api/login
      :parameters {:body UserCredentials}}]
    [""
     {:middleware [:sysadmin-check]}
     credits-routes
     ["/reaper"
      {:post api/cancel-dangling-builds}]]]])
