(ns monkey.ci.runners.oci
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [config :as config]
             [edn :as edn]
             [oci :as oci]
             [runners :as r]
             [runtime :as rt]
             [spec :as s]
             [utils :as u]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.spec.build :as sb]
            [monkey.ci.web.auth :as auth]
            [monkey.oci.container-instance.core :as ci]))

(def build-container "build")
(def format-sid (partial cs/join "/"))
(def ssh-keys-volume "ssh-keys")
(def ssh-keys-dir oci/key-dir)
(def log-config-volume "log-config")
(def log-config-dir "/home/monkeyci/config")
(def log-config-file "logback.xml")
(def config-volume "config")

(def build-ssh-keys (comp :ssh-keys :git))
(def log-config (comp :log-config :runner :config))

(defn- prepare-config-for-oci [config]
  (-> config
      ;; Enforce child runner
      ;; TODO Run in-process instead
      (assoc :runner {:type :child})))

(defn- add-ssh-keys-dir [build conf]
  (cond-> conf
    (build-ssh-keys build) (assoc-in [:build :git :ssh-keys-dir] ssh-keys-dir)))

(defn- add-log-config-path [rt conf]
  (cond-> conf
    (log-config rt) (assoc-in [:runner :log-config] (str log-config-dir "/" log-config-file))))

(defn- add-api-token
  "Generates a new API token that can be used by the build runner to invoke
   certain API calls."
  [build rt conf]
  (assoc-in conf [:api :token] (auth/generate-jwt-from-rt rt (auth/build-token (b/sid build)))))

(defn- rt->config [build rt]
  (->> (-> (rt/rt->config rt)
           (dissoc :app-mode :git :github :http :args :jwk :checkout-base-dir :storage
                   :ssh-keys-dir :work-dir :oci :runner)
           (assoc :build (dissoc build :ssh-keys :cleanup? :status))
           (update :events u/deep-merge (get-in rt [rt/config :runner :events])))
       (prepare-config-for-oci)
       (add-ssh-keys-dir build)
       (add-log-config-path rt)
       (add-api-token build rt)))

(defn- ->edn [build rt]
  (-> (rt->config build rt)
      (edn/->edn)))

(defn- ssh-keys-volume-config [build]
  (letfn [(->config-entries [idx ssh-keys]
            (map (fn [contents ext]
                   (oci/config-entry (format "key-%d%s" idx ext) contents))
                 ((juxt :private-key :public-key) ssh-keys)
                 ["" ".pub"]))]
    (when-let [ssh-keys (build-ssh-keys build)]
      {:name ssh-keys-volume
       :volume-type "CONFIGFILE"
       :configs (mapcat ->config-entries (range) ssh-keys)})))

(defn- add-ssh-keys-volume [conf build]
  (let [vc (ssh-keys-volume-config build)]
    (cond-> conf
      vc (update :volumes conj vc))))

(defn- add-ssh-keys-mount [conf build]
  (cond-> conf
    (build-ssh-keys build) (update :volume-mounts conj {:mount-path ssh-keys-dir
                                                        :is-read-only true
                                                        :volume-name ssh-keys-volume})))

(defn- log-config-volume-config [rt]
  (when-let [f (log-config rt)]
    (if (fs/exists? f)
      {:name log-config-volume
       :volume-type "CONFIGFILE"
       :configs [(oci/config-entry log-config-file (slurp f))]}
      (log/warn "Configured log config file does not exist:" f))))

(defn- add-log-config-volume [conf rt]
  (let [vc (log-config-volume-config rt)]
    (cond-> conf
      vc (update :volumes conj vc))))

(defn- add-log-config-mount [conf rt]
  (cond-> conf
    (log-config rt) (update :volume-mounts conj {:mount-path log-config-dir
                                                 :is-read-only true
                                                 :volume-name log-config-volume})))

(defn- add-config-volume [conf build rt]
  (update conf :volumes conj {:name config-volume
                              :volume-type "CONFIGFILE"
                              :configs [(oci/config-entry "config.edn"
                                                          (->edn build rt))]}))

(defn- add-config-mount [conf]
  (update conf :volume-mounts conj {:mount-path (-> config/*global-config-file*
                                                    (fs/parent)
                                                    str)
                                    :is-read-only true
                                    :volume-name config-volume}))

(defn- patch-container [[conf] build rt]
  (let [{:keys [url branch commit-id] :as git} (:git build)]
    (when (nil? url)
      (throw (ex-info "Git URL must be specified" {:git git})))
    ;; FIXME Also allow tags
    (when (and (nil? branch) (nil? commit-id))
      (throw (ex-info "Either branch or commit id must be specified" {:git git})))
    [(-> conf
         (assoc :display-name build-container
                ;; TODO Run build script in-process, it saves memory and time
                :arguments (cond-> ["-w" oci/checkout-dir "build" "run"
                                    "--sid" (format-sid (b/sid build))
                                    "-u" url]
                             ;; TODO Add support for tags as well
                             branch (concat ["-b" branch])
                             commit-id (concat ["--commit-id" commit-id]))
                ;; Run as root, because otherwise we can't write to the shared volumes
                :security-context {:security-context-type "LINUX"
                                   :run-as-user 0})
         (add-ssh-keys-mount build)
         (add-log-config-mount rt)
         (add-config-mount))]))

(defn instance-config
  "Creates container instance configuration using the context and the
   skeleton config."
  [conf build rt]
  (let [tags (oci/sid->tags (b/sid build))]
    (-> conf
        (oci/instance-config)
        (assoc :display-name (b/build-id build))
        ;; TODO Reduce this after we use in-process build
        ;;(assoc-in [:shape-config :memory-in-g-bs] 4)
        (update :freeform-tags merge tags)
        (update :containers patch-container build rt)
        (add-ssh-keys-volume build)
        (add-log-config-volume rt)
        (add-config-volume build rt))))

(defn wait-for-script-end-event
  "Returns a deferred that realizes when the script/end event has been received."
  [events sid]
  (ec/wait-for-event events
                     {:types #{:script/end}
                      :sid sid}))

(def max-script-timeout
  "Max msecs a build script can run before we terminate it"
  ;; One hour
  (* 3600 1000))

(defn oci-runner
  "Runs the build script as an OCI container instance.  Returns a deferred with
   the container exit code."
  [client conf build rt]
  (s/valid? ::sb/build build)
  (-> (oci/run-instance client (instance-config conf build rt)
                        {:delete? true
                         :exited? (fn [id]
                                    (md/chain
                                     (md/timeout!
                                      (wait-for-script-end-event (:events rt) (b/sid build))
                                      max-script-timeout ::timeout)
                                     (fn [r]
                                       (when (= r ::timeout)
                                         (log/warn "Build script timed out after" max-script-timeout "msecs"))
                                       (log/debug "Script end event received, fetching full instance details")
                                       (oci/get-full-instance-details client id))))})
      (md/chain
       (fn [r]
         (or (-> r :body :containers first :exit-code) 1)))
      ;; Do not launch build/end event, that is already done by the script container.
      ;; FIXME In case of a request error (e.g. 429 status) the build never finishes, since an end event is not sent.
      (md/catch
          (fn [ex]
            (log/error "Got error from container instance:" ex)
            (rt/post-events rt (b/build-end-evt build 1))))))

(defmethod r/make-runner :oci [rt]
  (let [conf (:runner rt)
        client (-> conf
                   (oci/->oci-config)
                   (ci/make-context))]
    (partial oci-runner client conf)))

(defmethod r/normalize-runner-config :oci [conf]
  (-> (oci/normalize-config conf :runner)
      (update-in [:runner :image-tag] #(format (or % "%s") (config/version)))
      (update :runner config/group-keys :events)
      ;; FIXME This is highly dependent on the events implementation
      (update-in [:runner :events] config/group-keys :client)))
