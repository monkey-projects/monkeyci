(ns monkey.ci.spec.build
  "Spec definitions for build.  The build object in the runtime and events that contain
   builds, scripts or jobs should conform to these specs."
  (:require [clojure.spec.alpha :as s]
            [monkey.ci.spec.common :as c]))

(def id? string?)
(def ts? int?)
(def path? string?)

(s/def ::start-time ts?)
(s/def ::end-time ts?)
(s/def ::message string?)

(s/def :script/checkout-dir path?)
(s/def :script/script-dir path?)
(s/def :script/status #{:pending :running :error :success})
(s/def :build/script (s/keys :req-un [:script/status :script/checkout-dir :script/script-dir]
                             :opt-un []))

(s/def :build/id id?)
(s/def :build/sid vector?)
(s/def :build/cleanup? boolean?)
(s/def :build/webhook-id id?)
(s/def :build/customer-id id?)
(s/def :build/repo-id id?)
(s/def :build/source keyword?)

;; GIT configuration
(s/def :git/url c/url?)
(s/def :git/ref string?)
(s/def :git/commit-id string?)
(s/def :git/dir path?)
(s/def :git/main-branch string?)
(s/def :git/ssh-keys-dir path?)
(s/def :build/git (s/keys :req-un [:git/url :git/dir]
                          :opt-un [:git/ref :git/commit-id :git/main-branch :git/ssh-keys-dir]))

(s/def ::build (s/keys :req-un [:build/customer-id :build/repo-id :build/build-id :build/sid
                                :build/source ::start-time]
                       :opt-un [:build/git :build/cleanup? :build/webhook-id ::end-time ::message
                                :build/script]))
