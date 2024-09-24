(ns monkey.ci.spec.build
  "Spec definitions for build.  The build object in the runtime and events that contain
   builds, scripts or jobs should conform to these specs."
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [monkey.ci.spec.common :as c]))

(def id? c/id?)
(def ts? c/ts?)
(def path? c/path?)

;; Start and end time for build, script, job, etc...
(s/def ::start-time ts?)
(s/def ::end-time ts?)
;; Informative message for the user, e.g. on failure
(s/def ::message string?)

(s/def ::generic-entity (s/keys :req-un [::start-time]
                                :opt-un [::end-time ::message]))

;;; Jobs: parts of a script

(s/def :job/id id?)
(s/def :job/type #{:action :container})
(s/def :job/status #{:pending :starting :running :stopping :error :failure :success :skipped})

(s/def :blob/id id?)
(s/def :blob/path path?)
(s/def :blob/size int?)
(s/def :job/dependencies (s/coll-of :blob/id))

(s/def ::blob
  (s/keys :req-un [:blob/id :blob/path]
          :opt-un [:blob/size]))
(s/def :job/caches (s/coll-of ::blob))
(s/def :job/save-artifacts (s/coll-of ::blob))
(s/def :job/restore-artifacts (s/coll-of ::blob))
(s/def :job/memory int?)
(s/def :job/cpus int?)
(s/def :job/arch #{:arm :amd})
(s/def :job/credit-multiplier int?)

(s/def :job/command string?)
(s/def :job/script (s/coll-of :job/command))

(s/def :script/job
  (-> (s/keys :req-un [:job/id]
              :opt-un [:job/type :job/dependencies :job/caches :job/save-artifacts :job/restore-artifacts
                       :job/script :job/memory :job/cpus :job/arch :job/status
                       :job/credit-multiplier])))

(s/def :script/jobs (s/coll-of :script/job))

;;; Script: contains info about a build script, most notably the jobs

(def build-states #{:pending :running :error :success :canceled})

(s/def :script/script-dir path?)
(s/def :script/status build-states)

(s/def :build/script
  (-> (s/keys :req-un [:script/status :script/script-dir]
              :opt-un [:script/jobs])
      (s/merge ::generic-entity)))

;;; Build: contains information about a single build, like id, git info, and script

(s/def :build/id id?)
(s/def :build/build-id id?)
(s/def :build/sid (s/coll-of id? :count 3))
(s/def :build/cleanup? boolean?)
(s/def :build/webhook-id id?)
(s/def :build/customer-id id?)
(s/def :build/repo-id id?)
(s/def :build/source #{:github-webhook :github-app :api})
(s/def :build/checkout-dir path?)
(s/def :build/status build-states)
(s/def :build/workspace string?)

;; GIT configuration
(s/def :git/url ::c/url)
(s/def :git/ref string?)
(s/def :git/commit-id string?)
(s/def :git/dir path?)
(s/def :git/main-branch string?)
(s/def :git/ssh-keys-dir path?)
(s/def :git/message string?)

(s/def :build/git
  (s/keys :req-un [:git/url :git/dir]
          :opt-un [:git/ref :git/commit-id :git/main-branch :git/ssh-keys-dir :git/message]))

;;; Changes: which files have changed for the build

(s/def :changes/added    (s/coll-of string?))
(s/def :changes/removed  (s/coll-of string?))
(s/def :changes/modified (s/coll-of string?))

(s/def :build/changes
  (s/keys :opt-un [:changes/added :changes/removed :changes/modified]))

(s/def ::build
  (-> (s/keys :req-un [:build/customer-id :build/repo-id :build/build-id :build/sid
                       :build/source]
              :opt-un [:build/git :build/cleanup? :build/webhook-id :build/script :build/checkout-dir
                       :build/changes :build/workspace :build/status])
      (s/merge ::generic-entity)))
