(ns monkey.ci.spec.common
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]]))

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

(s/def ::url (s/with-gen url?
               #(gen/fmap (partial format "http://%s") (gen/string-alphanumeric))))

(s/def ::storage (partial satisfies? p/Storage))
(s/def ::events (partial satisfies? p/EventPoster))
(s/def ::blob-store (partial satisfies? p/BlobStore))
(s/def ::artifacts ::blob-store)
(s/def ::cache ::blob-store)
(s/def ::workspace (partial satisfies? p/Workspace))
