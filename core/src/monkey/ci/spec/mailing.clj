(ns monkey.ci.spec.mailing
  (:require [clojure.spec.alpha :as s]))

(s/def ::subject string?)
(s/def ::text-body string?)
(s/def ::html-body string?)
(s/def ::to-users boolean?)
(s/def ::to-subscribers boolean?)
