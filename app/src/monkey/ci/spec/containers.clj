(ns monkey.ci.spec.containers
  "Container related config and context specs"
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(s/def ::org-id c/id?)
(s/def ::repo-id c/id?)
(s/def ::build-id c/id?)
(s/def ::job-id c/id?)
(s/def ::loki-url ::c/url)

(s/def ::token string?)
(s/def ::image-url string?)
(s/def ::image-tag string?)

(s/def ::promtail-config
  (s/keys :req-un [::org-id ::repo-id ::build-id ::job-id ::loki-url]
          :opt-un [::token ::image-url ::image-tag]))
