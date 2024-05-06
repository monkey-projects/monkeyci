(ns monkey.ci.spec.build
  "Spec definitions for build.  The build object in the runtime and events that contain
   builds, scripts or jobs should conform to these specs."
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec
             [common :as c]
             [job]]))

;; Informative message for the user, e.g. on failure
(s/def ::message string?)

;;; Script: contains info about a build script, most notably the jobs

(s/def :script/jobs (s/coll-of :job/props))

(def phases #{:pending :running :error :success :canceled})

(s/def :script/script-dir c/path?)
(s/def :script/phase phases)

(s/def :script/props
  (s/keys :req [:script/script-dir]
          :opt [:script/jobs]))

(s/def :script/status
  (-> (s/keys :req [:script/phase :job/states])
      (s/merge :entity/timed)))

;;; Build: contains information about a single build, like id, git info, and script

(s/def :customer/id c/id?)
(s/def :repo/id c/id?)
(s/def :webhook/id c/id?)

(s/def :build/id c/id?)
(s/def :build/cleanup? boolean?)
(s/def :build/source keyword?)
(s/def :build/checkout-dir c/path?)
(s/def :build/phase phases)

(s/def :git/main-branch string?)

;; GIT configuration
(s/def :git/url c/url?)
(s/def :git/ref string?)
(s/def :git/commit-id string?)

(s/def :git/props
  (s/keys :req [:git/url :git/changes]
          :opt [:git/ref :git/commit-id :git/main-branch]))

(s/def :git/dir c/path?)
(s/def :git/message string?)
(s/def :git/ssh-keys-dir c/path?)
(s/def :git/ssh-keys (s/coll-of :git/ssh-key))

(s/def :git/ssh-key (s/keys :req [:key/public :key/private]
                            :opt [:key/desc]))
(s/def :key/public string?)
(s/def :key/private string?)
(s/def :key/desc string?)

(s/def :git/status
  (s/keys :opt [:git/dir :git/ssh-keys :git/ssh-keys-dir :git/message]))

;;; Changes: which files have changed for the build

(s/def :changes/added    (s/coll-of string?))
(s/def :changes/removed  (s/coll-of string?))
(s/def :changes/modified (s/coll-of string?))

(s/def :git/changes
  (s/keys :opt-un [:changes/added :changes/removed :changes/modified]))

;;; Build properties

(s/def :build/props
  (s/keys :req [:customer/id :repo/id :build/id :build/source]
          :opt [:webhook/id :git/props :script/props]))

(s/def :build/status
  (-> (s/keys :req [:build/phase :build/cleanup? :git/status]
              :opt [:script/status :build/checkout-dir])
      (s/merge :entity/timed)))

(s/def :app/build
  (s/keys :req [:build/props]
          :opt [:build/status]))
