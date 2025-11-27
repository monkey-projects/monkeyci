(ns monkey.ci.common.schemas
  "Prismatic schemas, used by both backend and frontend"
  (:require [schema.core :as s]))

(def not-empty-str (s/constrained s/Str not-empty))
(def Id not-empty-str)
(def Name not-empty-str)

(defn- assoc-id [s]
  (assoc s (s/optional-key :id) Id))

(s/defschema Label
  {:name Name
   :value not-empty-str})

(s/defschema NewRepo
  {:org-id Id
   :name s/Str
   :url s/Str
   (s/optional-key :main-branch) s/Str
   (s/optional-key :public) s/Bool
   (s/optional-key :github-id) s/Int
   (s/optional-key :labels) [Label]})

(s/defschema UpdateRepo
  (assoc-id NewRepo))

(s/defschema Mailing
  {:subject s/Str
   (s/optional-key :text-body) s/Str
   (s/optional-key :html-body) s/Str})

(s/defschema SentMailing
  {:to-users s/Bool
   :to-subscribers s/Bool
   :other-dests [s/Str]})

(s/defschema EmailUnregistrationQuery
  {(s/optional-key :id) Id
   (s/optional-key :email) s/Str
   (s/optional-key :user-id) Id})
