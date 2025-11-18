(ns monkey.ci.common.schemas
  "Prismatic schemas, used by both backend and frontend"
  (:require [schema.core :as s]))

(s/defschema Mailing
  {:subject s/Str
   (s/optional-key :text-body) s/Str
   (s/optional-key :html-body) s/Str})

(s/defschema SentMailing
  {:to-users s/Bool
   :to-subscribers s/Bool
   :other-dests [s/Str]})
