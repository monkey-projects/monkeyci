(ns monkey.ci.spec.api-server
  "Api server configuration spec"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(s/def ::port ::c/port)
(s/def ::token ::c/token)
(s/def ::cache ::c/blob-store)
(s/def ::artifacts ::c/blob-store)
(s/def ::workspace ::c/blob-store)
(s/def ::params ::c/params)
(s/def ::mailman ::c/mailman)

(s/def ::base-config
  (s/keys :req-un [::artifacts ::params ::mailman]
          :opt-un [::cache ::workspace]))

(s/def ::app-config
  (-> (s/keys :opt-un [::port])
      (s/merge ::base-config)))

(s/def ::config
  (-> (s/keys :opt-un [::token])
      (s/merge ::app-config)))

