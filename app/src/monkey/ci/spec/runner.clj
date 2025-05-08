(ns monkey.ci.spec.runner
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as sc]))

(s/def ::config map?) ; TODO Specify

(s/def ::org-id (s/and string? not-empty))
(s/def ::repo-id (s/and string? not-empty))
(s/def ::sid (s/coll-of string?))

(s/def ::build
  (s/keys :req-un [::org-id ::repo-id]
          :opt-un [::sid]))

(s/def ::workspace ::sc/blob-store)
(s/def ::artifacts ::sc/blob-store)
(s/def ::cache ::sc/blob-store)
(s/def ::runner ifn?)
(s/def ::maker fn?)
(s/def ::logging (s/keys :req-un [::maker]))
(s/def ::mailman ::sc/mailman)

(s/def ::port int?)
(s/def ::token string?)
(s/def ::api-config
  (s/keys :req-un [::port ::token]))

(s/def ::runtime
  (s/keys :req-un [::config ::workspace ::artifacts ::cache ::mailman
                   ::git ::build ::api-config]))
