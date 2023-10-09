(ns monkey.ci.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async.impl.protocols :as ap]))

;; Unfortunately, there seems to be no clean way to determine
;; if something is a channel apart from accessing impl details.
(def channel? (partial satisfies? ap/Channel))

;; Event related spec
(s/def :evt/type keyword?)
(s/def :evt/message string?)
(s/def :evt/time int?)
(s/def :evt/src keyword?)
(s/def :evt/event (s/keys :req-un [:evt/type :evt/time]
                          :opt-un [:evt/message :evt/src]))
(s/def :evt/channel channel?)

(s/def :evt/event-bus (s/keys :req-un [:evt/channel :evt/pub]))
(s/def :evt/handler fn?)
(s/def :evt/tx fn?)
(s/def :evt/loop channel?)
(s/def :evt/event-handler (s/keys :req-un [:evt/channel :evt/type]
                                  :opt-un [:evt/handler :evt/tx :evt/loop]))

;; Application configuration spec

(s/def :conf/dev-mode boolean?)
;; HTTP server configuration
(s/def :http/port int?)
(s/def :conf/http (s/keys :req-un [:http/port]))

(s/def :runner/type keyword?)
(s/def :conf/runner (s/keys :req-un [:runner/type]))

;; Container runner configuration
(s/def :containers/type #{:docker :podman :oci})
(s/def :conf/containers (s/keys :req-un [:containers/type]))

;; Storage configuration
(s/def :storage/type #{:file :oci})
(s/def :storage/dir string?)
(s/def :storage/bucket string?)
(s/def :conf/storage (s/keys :req-un [:storage/type]
                             :opt-un [:storage/dir :storage/bucket]))

(s/def :conf/log-dir string?)
(s/def :conf/work-dir string?)
(s/def :conf/checkout-base-dir string?)

;; Command line arguments
(s/def :arg/pipeline string?)
(s/def :arg/dir string?)
(s/def :arg/workdir string?)
(s/def :arg/git-url string?)
(s/def :arg/config-file string?)

(s/def :arg/command fn?)

;; GIT configuration
(s/def :git/url string?)
(s/def :git/branch string?)
(s/def :git/id string?)
(s/def :git/fn fn?)
(s/def :git/dir string?)
(s/def :build/git (s/keys :req-un [:git/url :git/dir]
                          :opt-un [:git/branch :git/id]))
(s/def :ctx/git (s/keys :req-un [:git/fn]))

(s/def :ctx/runner fn?)
(s/def :build/script-dir string?)
(s/def :build/checkout-dir string?)
(s/def :build/build-id string?)
(s/def :ctx/build (s/keys :req-un [:build/script-dir :build/build-id :build/checkout-dir]
                          :opt-un [:conf/pipeline :build/git]))

;; Arguments as passed in from the CLI
(s/def :conf/args (s/keys :opt-un [:conf/dev-mode :arg/pipeline :arg/dir :arg/workdir
                                   :arg/git-url :arg/config-file]))

;; Application configuration
(s/def ::app-config (s/keys :req-un [:conf/http :conf/runner :conf/args
                                     :conf/work-dir :conf/checkout-base-dir]
                            :opt-un [:conf/dev-mode :conf/containers :conf/log-dir :conf/storage]))
;; Application context.  This is the result of processing the configuration and is passed
;; around internally.
(s/def ::app-context (s/keys :req-un [:conf/http :ctx/runner :evt/event-bus :ctx/git]
                             :opt-un [:conf/dev-mode :arg/command ::system :conf/args :ctx/build]))

;; Script configuration
(s/def ::script-config (s/keys :req-un [:conf/containers :conf/storage]))
