(ns monkey.ci.spec.gen
  "Custom spec generators"
  (:require [clojure.spec.gen.alpha :as gen]
            [clojure.string :as cs]))

(def byte*
  (gen/fmap byte (gen/choose Byte/MIN_VALUE Byte/MAX_VALUE)))

(defn fixed-byte-array
  "Generates fixed-size byte arrays"
  [size]
  (gen/fmap byte-array (gen/vector byte* size)))

(defn fixed-string
  "Generates fixed-size strings"
  [size]
  (gen/fmap cs/join (gen/vector (gen/char-alphanumeric) size)))
