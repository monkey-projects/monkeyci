(ns monkey.ci.spec.build
  "Common spec definitions for builds.  These are used by domain-specific specs for build objects."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [monkey.ci.spec
             [common :as c]
             [git :as git]
             [script :as ss]]
            [monkey.ci.spec.job.common :as jc]))

(def id? c/id?)
(def ts? c/ts?)
(def path? c/path?)
(def build-states #{:pending :initializing :running :error :success :canceled})

(s/def ::sid (s/coll-of id? :count 3))
(s/def ::status build-states)
(s/def ::build-id id?)
(s/def ::idx pos-int?)
;; Informative message for the user, e.g. on failure
(s/def ::message string?)
(s/def ::source #{:github-webhook :github-app :api :bitbucket-webhook :cli})

(s/def ::script
  (-> (s/keys :opt-un [::ss/script-dir])
      (s/merge ::c/timed)))

;;; Build: contains information about a single build, like id, git info, and script

(s/def ::cleanup? boolean?)
(s/def ::webhook-id id?)
(s/def ::org-id id?)
(s/def ::repo-id id?)
(s/def ::checkout-dir path?)
(s/def ::workspace string?)
(s/def ::dek string?)

;;; Changes: which files have changed for the build

(s/def :changes/added    (s/coll-of string?))
(s/def :changes/removed  (s/coll-of string?))
(s/def :changes/modified (s/coll-of string?))

(s/def ::changes
  (s/keys :opt-un [:changes/added :changes/removed :changes/modified]))

;; Optional build parameters, override existing params
(s/def ::params (s/map-of string? string?))

(s/def ::build
  (-> (s/keys :req-un [::org-id ::repo-id ::sid ::source]
              ;; Build id and idx are assigned when build is queued
              :opt-un [::build-id ::idx ::git/git ::cleanup? ::webhook-id ::script
                       ::checkout-dir ::changes ::workspace ::status ::c/timeout
                       ::dek ::params ::message])
      (s/merge ::c/timed)))
