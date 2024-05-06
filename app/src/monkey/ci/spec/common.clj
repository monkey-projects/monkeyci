(ns monkey.ci.spec.common
  (:require [clojure.spec.alpha :as s]))

(def url-regex #"^(?:([A-Za-z]+):)(\/{0,3})([0-9.\-A-Za-z]+)(?::(\d+))?(?:\/([^?#]*))?(?:\?([^#]*))?(?:#(.*))?$")

(defn url? [x]
  (and (string? x) (re-matches url-regex x)))

(def id? string?)
(def ts? int?) ; Timestamp
(def path? string?)

(s/def :time/start ts?)
(s/def :time/end   ts?)
(s/def :entity/timed (s/keys :req [:time/start]
                             :opt [:time/end]))
