(ns monkey.ci.web.admin
  "API Route definitions for administrative purposes.  These are not available
   in the general api and are meant to be used by system administrators or
   system processes only."
  (:require [monkey.ci.web.api.admin :as api]
            [schema.core :as s]))

(s/defschema UserCredits
  {:amount s/Int
   (s/optional-key :reason) s/Str})

(def admin-routes
  [["/issue-credits"
    {:conflicting true}
    [["/auto"
      {:post api/issue-auto-credits}]
     ["/:customer-id"
      {:post api/issue-credits
       :parameters {:body UserCredits}}]]]])
