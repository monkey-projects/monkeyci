(ns monkey.ci.spec.context
  "Job context spec"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec
             [build :as b]
             [common :as c]]))

;; The context is passed to a job fn
(s/def ::context (s/keys :req-un [::api ::b/build :script/job]))

;; Needed to create a client
(s/def :api/url ::c/url)
(s/def :api/token string?)

;; The api client itself is just a fn
(s/def ::api fn?)
