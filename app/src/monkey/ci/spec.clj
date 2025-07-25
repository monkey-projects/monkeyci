(ns monkey.ci.spec
  (:require [buddy.core.keys :as bk]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [monkey.ci.protocols :as p]
            [monkey.ci.spec.common :as c]
            [monkey.oci.sign :as oci-sign]))

(defn valid?
  "Checks if the object is conform to the spec, and also prints a handy warning
   if not."
  [spec x]
  (if-not (s/valid? spec x)
    (do (log/warn "Object does not match spec:" x ", spec:" spec ", explanation:" (s/explain-str spec x))
        false)
    true))

(def url? c/url?)

;; Event related spec
(s/def :evt/type keyword?)
(s/def :evt/message string?)
(s/def :evt/time int?)
(s/def :evt/src keyword?)
(s/def :evt/event (s/keys :req-un [:evt/type :evt/time]
                          :opt-un [:evt/message :evt/src]))

;;; Application configuration spec

;; App mode: how is this application being run?  Mode influences how the
;; runtime is set up.
(s/def :conf/app-mode #{:server :cli :script})

(s/def :conf/dev-mode boolean?)
;; HTTP server configuration
(s/def :http/port int?)
(s/def :conf/http (s/keys :req-un [:http/port]))

(s/def :runner/type keyword?)
(s/def :conf/runner (s/keys :req-un [:runner/type]))

;; Container runner configuration
(s/def :containers/type #{:podman :oci})
(s/def :containers/platform string?)
(s/def :conf/containers (s/keys :req-un [:containers/type]))

;; Storage configuration
(s/def :storage/type #{:memory :file :oci :sql})
(s/def :storage/dir string?)
(s/def :oci/bucket-name string?)
(s/def :oci/credentials (s/merge ::oci-sign/config))
(s/def :oci/region string?)
(s/def :oci/prefix string?)
(s/def :oci/ns string?)

(defmulti storage-type :type)

(defmethod storage-type :memory [_]
  (s/keys :req-un [:storage/type]))

(defmethod storage-type :file [_]
  (s/keys :req-un [:storage/type :storage/dir]))

(defmethod storage-type :oci [_]
  (s/keys :req-un [:storage/type :oci/bucket-name :oci/region]
          :opt-un [:oci/credentials]))

(s/def :sql/url string?)
(s/def :sql/username string?)
(s/def :sql/password string?)

(defmethod storage-type :sql [_]
  (s/keys :req-un [:storage/type :sql/url]
          :opt-un [:sql/username :sql/password]))

(s/def :conf/storage (s/multi-spec storage-type :type))

(defmulti blob-type :type)

(s/def :blob/store p/blob-store?)
(s/def :blob/type #{:disk :oci :s3})
(s/def :disk-blob/dir string?)

(defmethod blob-type :disk [_]
  (s/keys :req-un [:blob/type :disk-blob/dir]))

(s/def :oci-blob/tmp-dir string?)

(defmethod blob-type :oci [_]
  (s/keys :req-un [:blob/type :oci/bucket-name :oci/ns]
          :opt-un [:oci/credentials :oci/prefix :oci-blob/tmp-dir]))

(s/def :s3/bucket-name string?)
(s/def :s3/endpoint string?)
(s/def :s3/access-key string?)
(s/def :s3/secret-key string?)
(s/def :s3/prefix string?)

(defmethod blob-type :s3 [_]
  (s/keys :req-un [:blob/type :s3/bucket-name :s3/endpoint]
          :opt-un [:s3/prefix :s3/access-key :s3/secret-key]))

(s/def :conf/blob (s/multi-spec blob-type :type))

(s/def :conf/workspace (s/merge :conf/blob))
(s/def :conf/artifacts (s/merge :conf/blob))
(s/def :conf/cache (s/merge :conf/blob))

(s/def :conf/log-dir string?)
(s/def :conf/work-dir string?)
(s/def :conf/checkout-base-dir string?)
(s/def :conf/ssh-keys-dir string?)
(s/def :conf/url url?)

;; Account configuration
(s/def :conf/org-id string?)
(s/def :conf/project-id string?)
(s/def :conf/repo-id string?)
(s/def :conf/build-id string?)
(s/def :conf/account (s/keys :req-un [:conf/org-id]
                             :opt-un [:conf/url :conf/project-id :conf/repo-id]))

(s/def :conf/socket string?)
(s/def :conf/api (s/keys :opt-un [:conf/socket :conf/url]))

;; Sidecar config
(s/def :sidecar/poll-interval int?)
(s/def :sidecar/log-config string?)
(s/def :conf/sidecar (s/keys :opt-un [:sidecar/poll-interval :sidecar/log-config]))

(s/def :conf-jwk/pub (partial instance? java.security.PublicKey))
(s/def :conf-jwk/priv (partial instance? java.security.PrivateKey))
(s/def :conf/jwk (s/keys :req-un [:conf-jwk/pub :conf-jwk/priv]))

;; Command line arguments
(s/def :arg/pipeline string?)
(s/def :arg/dir string?)
(s/def :arg/workdir string?)
(s/def :arg/git-url url?)
(s/def :arg/config-file string?)
(s/def :arg/events-file string?)

(s/def :arg/command fn?)

;; GIT configuration
(s/def :git/url url?)
(s/def :git/branch string?)
(s/def :git/id string?)
(s/def :git/fn fn?)
(s/def :git/clone fn?)
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

(s/def :ctx/workspace (s/merge :conf/workspace
                               (s/keys :req-un [:blob/store])))

(defmulti logging-type :type)

(defmethod logging-type :inherit [_]
  (s/merge :logging/config))

(defmethod logging-type :file [_]
  (s/merge :logging/config (s/keys :opt-un [:logging/dir])))

(defmethod logging-type :oci [_]
  (s/merge :logging/config (s/keys :req-un [:oci/bucket-name :oci/ns]
                                   :opt-un [:oci/credentials :oci/prefix])))

(s/def :conf/logging (s/multi-spec logging-type :type))
(s/def :ctx/logging (s/keys :req-un [:logging/maker :logging/retriever]))

(s/def :jwk/priv bk/private-key?)
(s/def :jwk/pub bk/public-key?)
(s/def :ctx/jwk (s/keys :req-un [:jwk/priv :jwk/pub]))

(s/def :ctx/storage (partial satisfies? monkey.ci.protocols/Storage))
(s/def :ctx/containers (s/merge :conf/containers))

;; Arguments as passed in from the CLI
(s/def :conf/args (s/keys :opt-un [:conf/dev-mode :arg/pipeline :arg/dir :arg/workdir
                                   :arg/git-url :arg/config-file :arg/events-file]))

;; Application configuration
(s/def ::app-config (s/keys :req-un [:conf/http :conf/runner :conf/logging :conf/containers
                                     :conf/storage :conf/workspace :conf/artifacts :conf/cache]
                            :opt-un [:conf/app-mode :conf/work-dir :conf/checkout-base-dir :conf/ssh-keys-dir
                                     :conf/dev-mode :conf/args :conf/jwk :conf/account :conf/sidecar]))
;; Application context.  This is the result of processing the configuration and is passed
;; around internally.
(s/def ::app-context (s/keys :req-un [:conf/http :ctx/runner :ctx/git :ctx/public-api
                                      :ctx/logging]
                             :opt-un [:conf/dev-mode :arg/command ::system :evt/event-bus :conf/args
                                      :ctx/build :ctx/reporter :conf/work-dir :conf/sidecar :ctx/jwk]))

;; Script configuration
(s/def ::script-config (s/keys :req-un [:conf/containers :conf/storage :conf/logging]
                               :opt-un [:conf/api]))

(s/def :rt/logging (s/merge :ctx/logging))
(s/def :rt/runner :ctx/runner)
(s/def :rt/logging (s/merge :ctx/logging))
(s/def :rt/containers (s/merge :ctx/containers))
(s/def :rt/blob (s/keys :req-un [:blob/store]))
(s/def :rt/workspace (s/merge :rt/blob))
(s/def :rt/artifacts (s/merge :rt/blob))
(s/def :rt/cache (s/merge :rt/blob))
(s/def :rt/git (s/keys :req-un [:git/clone]))

(s/def ::runtime (s/keys :req-un [:rt/logging :rt/runner :rt/storage :rt/workspace :rt/artifacts :rt/cache]
                         :opt-un [:rt/containers :rt/git]))
