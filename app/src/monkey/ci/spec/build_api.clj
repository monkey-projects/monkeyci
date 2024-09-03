(ns monkey.ci.spec.build-api
  (:require [clojure.spec.alpha :as s]))


(s/def ::url (s/and string? not-empty))
(s/def ::token (s/and string? not-empty))

(s/def ::api
  (s/keys :req-un [::url ::token]))
