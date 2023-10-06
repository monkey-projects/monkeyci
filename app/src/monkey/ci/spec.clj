(ns monkey.ci.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async.impl.protocols :as ap]))

;; Unfortunately, there seems to be no clean way to determine
;; if something is a channel apart from accessing impl details.
(def channel? (partial satisfies? ap/Channel))

(s/def ::type keyword?)
(s/def ::message string?)
(s/def ::time int?)
(s/def :evt/src keyword?)
(s/def ::event (s/keys :req-un [::type ::time]
                       :opt-un [::message :evt/src]))
(s/def ::channel channel?)

(s/def ::event-bus (s/keys :req-un [::channel ::pub]))
(s/def ::handler fn?)
(s/def ::tx fn?)
(s/def ::loop channel?)
(s/def ::event-handler (s/keys :req-un [::channel ::type]
                               :opt-un [::handler ::tx ::loop]))

(s/def ::dev-mode boolean?)
;; HTTP server configuration
(s/def ::port int?)
(s/def ::http (s/keys :req-un [::port]))

(s/def :conf/runner (s/keys :req-un [::type]))

(s/def ::containers (s/keys :req-un [::type]))
(s/def ::pipeline string?)
(s/def :conf/dir string?)
(s/def :conf/workdir string?)
(s/def :conf/git-url string?)
(s/def :conf/config-file string?)
(s/def :conf/log-dir string?)

(s/def ::command fn?)
(s/def :ctx/runner fn?)

;; GIT configuration
(s/def ::url string?)
(s/def :git/branch string?)
(s/def :git/id string?)
(s/def :git/fn fn?)
(s/def ::dir string?)
(s/def :ctx/git (s/keys :req-un [::url ::dir]
                        :opt-un [:git/branch :git/id]))
(s/def :conf/git (s/keys :req-un [:git/fn]))

(s/def ::script-dir string?)
(s/def ::work-dir string?)
(s/def ::build-id string?)
(s/def :ctx/build (s/keys :req-un [::script-dir ::work-dir ::build-id]
                          :opt-un [::pipeline :ctx/git]))

;; Arguments as passed in from the CLI
(s/def ::args (s/keys :opt-un [::dev-mode ::pipeline :conf/dir :conf/workdir :conf/git-url :conf/config-file]))

;; Application configuration
(s/def ::app-config (s/keys :req-un [::http :conf/runner ::args]
                            :opt-un [::dev-mode :conf/log-dir]))

(s/def ::app-context (s/keys :req-un [::http :ctx/runner ::event-bus :conf/git]
                             :opt-un [::dev-mode ::command ::system ::args :ctx/build]))

;; Script configuration
(s/def ::script-config (s/keys :req-un [::containers]))
