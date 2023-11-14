(ns monkey.ci.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.core.async.impl.protocols :as ap]
            [monkey.oci.sign :as oci-sign]))

;; Unfortunately, there seems to be no clean way to determine
;; if something is a channel apart from accessing impl details.
(def channel? (partial satisfies? ap/Channel))

(def url-regex #"^(?:([A-Za-z]+):)(\/{0,3})([0-9.\-A-Za-z]+)(?::(\d+))?(?:\/([^?#]*))?(?:\?([^#]*))?(?:#(.*))?$")

(defn url? [x]
  (and (string? x) (re-matches url-regex x)))

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
(s/def :storage/type #{:memory :file :oci})
(s/def :storage/dir string?)
(s/def :oci/bucket-name string?)
(s/def :oci/credentials (s/merge ::oci-sign/config))
(s/def :oci/region string?)

(defmulti storage-type :type)

(defmethod storage-type :memory [_]
  (s/keys :req-un [:storage/type]))

(defmethod storage-type :file [_]
  (s/keys :req-un [:storage/type :storage/dir]))

(defmethod storage-type :oci [_]
  (s/keys :req-un [:storage/type :oci/bucket-name :oci/region]
          :opt-un [:oci/credentials]))

(s/def :conf/storage (s/multi-spec storage-type :type))

(s/def :conf/log-dir string?)
(s/def :conf/work-dir string?)
(s/def :conf/checkout-base-dir string?)
(s/def :conf/url url?)

;; Account configuration
(s/def :conf/customer-id string?)
(s/def :conf/project-id string?)
(s/def :conf/repo-id string?)
(s/def :conf/build-id string?)
(s/def :conf/account (s/keys :req-un [:conf/customer-id]
                             :opt-un [:conf/url :conf/project-id :conf/repo-id]))

(s/def :conf/socket string?)
(s/def :conf/api (s/keys :opt-un [:conf/socket :conf/url]))

;; Command line arguments
(s/def :arg/pipeline string?)
(s/def :arg/dir string?)
(s/def :arg/workdir string?)
(s/def :arg/git-url url?)
(s/def :arg/config-file string?)

(s/def :arg/command fn?)

;; GIT configuration
(s/def :git/url url?)
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
(s/def :build/coords vector?)
(s/def :build/build-id string?)
(s/def :ctx/build (s/keys :req-un [:build/script-dir :build/build-id :build/checkout-dir]
                          :opt-un [:conf/pipeline :build/git :build/coords]))

(s/def :ctx/public-api fn?)
(s/def :ctx/reporter fn?)

(s/def :logging/type #{:file :inherit :oci})
(s/def :logging/config (s/keys :req-un [:logging/type]))
(s/def :logging/dir string?)
(s/def :logging/maker fn?)

(defmulti logging-type :type)

(defmethod logging-type :inherit [_]
  (s/merge :logging/config))

(defmethod logging-type :file [_]
  (s/merge :logging/config (s/keys :opt-un [:logging/dir])))

(defmethod logging-type :oci [_]
  (s/merge :logging/config (s/keys :req-un [:logging/dir :oci/bucket-name]
                                   :opt-un [:oci/credentials])))

(s/def :conf/logging (s/multi-spec logging-type :type))
(s/def :ctx/logging (s/merge :conf/logging
                             (s/keys :req-un [:logging/maker])))

;; Arguments as passed in from the CLI
(s/def :conf/args (s/keys :opt-un [:conf/dev-mode :arg/pipeline :arg/dir :arg/workdir
                                   :arg/git-url :arg/config-file]))

;; Application configuration
(s/def ::app-config (s/keys :req-un [:conf/http :conf/runner :conf/args :conf/logging
                                     :conf/work-dir :conf/checkout-base-dir]
                            :opt-un [:conf/dev-mode :conf/containers :conf/log-dir
                                     :conf/storage :conf/account]))
;; Application context.  This is the result of processing the configuration and is passed
;; around internally.
(s/def ::app-context (s/keys :req-un [:conf/http :ctx/runner :evt/event-bus :ctx/git :ctx/storage :ctx/public-api
                                      :ctx/logging]
                             :opt-un [:conf/dev-mode :arg/command ::system :conf/args :ctx/build :ctx/reporter
                                      :conf/work-dir]))

;; Script configuration
(s/def ::script-config (s/keys :req-un [:conf/containers :conf/storage :conf/logging]
                               :opt-un [:conf/api]))
