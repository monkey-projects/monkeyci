(ns monkey.ci.spec.build
  "Spec definitions for build.  The build object in the runtime and events that contain
   builds, scripts or jobs should conform to these specs."
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(def id? string?)
(def ts? int?)
(def path? string?)

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
(s/def :job/artifacts (s/coll-of ::blob))

(s/def :job/command string?)
(s/def :job/commands (s/coll-of :job/command))

(s/def :script/job
  (-> (s/keys :req-un [:job/id :job/type :job/status]
              :opt-un [:job/dependencies :job/caches :job/artifacts :job/commands])
      (s/merge ::generic-entity)))

(s/def :script/jobs (s/coll-of :script/job))

;;; Script: contains info about a build script, most notably the jobs

(def build-states #{:pending :running :error :success})

(s/def :script/script-dir path?)
(s/def :script/status build-states)

(s/def :build/script
  (-> (s/keys :req-un [:script/status :script/script-dir]
              :opt-un [:script/jobs])
      (s/merge ::generic-entity)))

;;; Build: contains information about a single build, like id, git info, and script

(s/def :build/id id?)
(s/def :build/sid vector?)
(s/def :build/cleanup? boolean?)
(s/def :build/webhook-id id?)
(s/def :build/customer-id id?)
(s/def :build/repo-id id?)
(s/def :build/source keyword?)
(s/def :build/checkout-dir path?)
(s/def :build/status build-states)

;; GIT configuration
(s/def :git/url c/url?)
(s/def :git/ref string?)
(s/def :git/commit-id string?)
(s/def :git/dir path?)
(s/def :git/main-branch string?)
(s/def :git/ssh-keys-dir path?)
(s/def :git/message string?)

(s/def :build/git
  (s/keys :req-un [:git/url :git/dir]
          :opt-un [:git/ref :git/commit-id :git/main-branch :git/ssh-keys-dir :git/message]))

(s/def ::build
  (-> (s/keys :req-un [:build/customer-id :build/repo-id :build/build-id :build/sid
                       :build/source :build/status]
              :opt-un [:build/git :build/cleanup? :build/webhook-id :build/script :build/checkout-dir])
      (s/merge ::generic-entity)))
