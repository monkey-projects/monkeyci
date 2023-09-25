(ns monkey.ci.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async.impl.protocols :as ap]))

;; Unfortunately, there seems to be no clean way to determine
;; if something is a channel apart from accessing impl details.
(def channel? (partial satisfies? ap/Channel))

(s/def ::type keyword?)
(s/def ::message string?)
(s/def ::event (s/keys :req-un [::type]
                       :opt-un [::message]))
(s/def ::channel channel?)

(s/def ::event-bus (s/keys :req-un [::channel ::pub]))
(s/def ::handler fn?)
(s/def ::tx fn?)
(s/def ::loop channel?)
(s/def ::event-handler (s/keys :req-un [::channel ::type]
                               :opt-un [::handler ::tx ::loop]))


;; HTTP server configuration
(s/def ::port int?)
(s/def ::http (s/keys :req-un [::port]))

(s/def :conf/runner (s/keys :req-un [::type]))

(s/def ::containers (s/keys :req-un [::type]))

;; Application configuration
(s/def ::app-config (s/keys :req-un [::http :conf/runner]))

(s/def ::command fn?)
(s/def :ctx/runner fn?)
(s/def ::args map?)
(s/def ::app-context (s/keys :req-un [::http :ctx/runner ::event-bus]
                             :opt-un [::command ::system ::args]))

;; Script configuration
(s/def ::script-config (s/keys :req-un [::containers]))
