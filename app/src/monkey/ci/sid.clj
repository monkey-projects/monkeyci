(ns monkey.ci.sid
  "Functions for working with storage ids"
  (:require [clojure.string :as cs]))

(def delim "/")

(def sid? vector?)
(def ->sid vec)

(defn parse-sid [s]
  (cond-> s
    (string? s) (cs/split #"/")))

(def serialize-sid (partial cs/join "/"))

(defn sid->repo-sid [s]
  (take 2 s))
