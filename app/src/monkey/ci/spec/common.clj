(ns monkey.ci.spec.common)

(def id? string?)
(def ts? int?)
(def path? string?)

(def url-regex #"^(?:([A-Za-z]+):)(\/{0,3})([0-9.\-A-Za-z]+)(?::(\d+))?(?:\/([^?#]*))?(?:\?([^#]*))?(?:#(.*))?$")

(defn url? [x]
  (and (string? x) (re-matches url-regex x)))
