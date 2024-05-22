(ns monkey.ci.spec.common
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [monkey.ci.cuid :as cuid]))

(def id? string?)
(def ts? int?)
(def path? string?)
(def cuid? cuid/cuid?)

(def url-regex #"^(?:([A-Za-z]+):)(\/{0,3})([0-9.\-A-Za-z]+)(?::(\d+))?(?:\/([^?#]*))?(?:\?([^#]*))?(?:#(.*))?$")

(defn url? [x]
  (and (string? x) (re-matches url-regex x)))

(s/def ::cuid (s/with-gen
                cuid/cuid?
                #(gen/fmap clojure.string/join
                           (gen/vector (gen/elements cuid/cuid-chars) cuid/cuid-length))))

