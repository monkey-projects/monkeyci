(ns monkey.ci.spec.api-server
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(s/def ::port int?)
(s/def ::token string?)
(s/def ::cache ::c/blob-store)
(s/def ::artifacts ::c/blob-store)
(s/def ::workspace ::c/blob-store)
(s/def ::params ::c/params)
(s/def ::mailman ::c/mailman)

(s/def ::base-config
  (s/keys :req-un [::artifacts ::params ::mailman]
          :opt-un [::cache ::workspace]))

(s/def ::config
  (-> (s/merge ::base-config
               (s/keys :opt-un [::port ::token]))))

(s/def ::app-config
  (-> (s/merge ::base-config
               (s/keys :opt-un [::token]))))
