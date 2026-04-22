(ns monkey.ci.spec.build-api
  "Spec for the build api configuration and client, used in scripts"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as sc]))

(s/def ::url (s/and string? not-empty))
(s/def ::token ::sc/token)
(s/def ::port ::sc/port)

(s/def ::api
  (s/and (s/keys :req-un [::token]
                 :opt-un [::url ::port])
         (some-fn :url :port)))

(s/def ::client fn?)
