(ns monkey.ci.runners.oci
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [config :as config]
             [oci :as oci]
             [runners :as r]
             [runtime :as rt]
             [utils :as u]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.web.auth :as auth]
            [monkey.oci.container-instance.core :as ci]))

(def build-container "build")
(def format-sid (partial cs/join "/"))
(def ssh-keys-volume "ssh-keys")
(def ssh-keys-dir oci/key-dir)
(def log-config-volume "log-config")
(def log-config-dir "/home/monkeyci/config")
(def log-config-file "logback.xml")

(def build-ssh-keys (comp :ssh-keys :git :build))
(def log-config (comp :log-config :runner :config))

(defn- prepare-config-for-oci [config]
  (-> config
      ;; Enforce child runner
      ;; TODO Run in-process instead
      (assoc :runner {:type :child})))

(defn- add-ssh-keys-dir [rt conf]
  (cond-> conf
    (build-ssh-keys rt) (assoc-in [:build :git :ssh-keys-dir] ssh-keys-dir)))

(defn- add-log-config-path [rt conf]
  (cond-> conf
    (log-config rt) (assoc-in [:runner :log-config] (str log-config-dir "/" log-config-file))))

(defn- add-api-token
  "Generates a new API token that can be used by the build runner to invoke
   certain API calls."
  [rt conf]
  (assoc-in conf [:api :token] (auth/generate-jwt-from-rt rt (auth/build-token (b/get-sid rt)))))

(defn- ->env [rt]
  (->> (-> (rt/rt->env rt)
           (dissoc :app-mode :git :github :http :args :jwk :checkout-base-dir :storage
                   :ssh-keys-dir :work-dir :oci :runner)
           (update :build dissoc :cleanup? :status)
           (update-in [:build :git] dissoc :ssh-keys)
           (update :events (partial merge-with merge) (get-in rt [rt/config :runner :events])))
       (prepare-config-for-oci)
       (add-ssh-keys-dir rt)
       (add-log-config-path rt)
       (add-api-token rt)
       (config/config->env)
       (mc/map-keys name)
       (mc/remove-vals empty?)))

(defn- ssh-keys-volume-config [rt]
  (letfn [(->config-entries [idx ssh-keys]
            (map (fn [contents ext]
                   (oci/config-entry (format "key-%d%s" idx ext) contents))
                 ((juxt :private-key :public-key) ssh-keys)
                 ["" ".pub"]))]
    (when-let [ssh-keys (build-ssh-keys rt)]
      {:name ssh-keys-volume
       :volume-type "CONFIGFILE"
       :configs (mapcat ->config-entries (range) ssh-keys)})))

(defn- add-ssh-keys-volume [conf rt]
  (let [vc (ssh-keys-volume-config rt)]
    (cond-> conf
      vc (update :volumes conj vc))))

(defn- add-ssh-keys-mount [conf rt]
  (cond-> conf
    (build-ssh-keys rt) (update :volume-mounts conj {:mount-path ssh-keys-dir
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

(defn- patch-container [[conf] rt]
  (let [{:keys [url branch commit-id] :as git} (get-in rt [:build :git])]
    (when (nil? url)
      (throw (ex-info "Git URL must be specified" {:git git})))
    ;; FIXME Also allow tags
    (when (and (nil? branch) (nil? commit-id))
      (throw (ex-info "Either branch or commit id must be specified" {:git git})))
    [(-> conf
         (assoc :display-name build-container
                ;; TODO Run build script in-process, it saves memory and time
                :arguments (cond-> ["-w" oci/checkout-dir "build" "run"
                                    "--sid" (format-sid (b/get-sid rt))
                                    "-u" url]
                             ;; TODO Add support for tags as well
                             branch (concat ["-b" branch])
                             commit-id (concat ["--commit-id" commit-id]))
                ;; TODO Pass config in a config file instead of env, cleaner and somewhat safer
                :environment-variables (->env rt)
                ;; Run as root, because otherwise we can't write to the shared volumes
                :security-context {:security-context-type "LINUX"
                                   :run-as-user 0})
         (add-ssh-keys-mount rt)
         (add-log-config-mount rt))]))

(defn instance-config
  "Creates container instance configuration using the context and the
   skeleton config."
  [conf rt]
  (let [tags (oci/sid->tags (b/get-sid rt))]
    (-> conf
        (update :image-tag #(or % (config/version)))
        (oci/instance-config)
        (assoc :display-name (b/get-build-id rt))
        ;; TODO Reduce this after we use in-process build
        ;;(assoc-in [:shape-config :memory-in-g-bs] 4)
        (update :freeform-tags merge tags)
        (update :containers patch-container rt)
        (add-ssh-keys-volume rt)
        (add-log-config-volume rt))))

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
  [client conf rt]
  (-> (oci/run-instance client (instance-config conf rt)
                        {:delete? true
                         :exited? (fn [id]
                                    (md/chain
                                     (md/timeout!
                                      (wait-for-script-end-event (:events rt) (b/get-sid rt))
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
      (md/catch
          (fn [ex]
            (log/error "Got error from container instance:" ex)
            (rt/post-events rt (b/build-end-evt (rt/build rt) 1))))))

(defmethod r/make-runner :oci [rt]
  (let [conf (:runner rt)
        client (-> conf
                   (oci/->oci-config)
                   (ci/make-context))]
    (partial oci-runner client conf)))

(defmethod r/normalize-runner-config :oci [conf]
  (-> (oci/normalize-config conf :runner)
      (update-in [:runner :image-tag] #(or % (config/version)))
      (update :runner config/group-keys :events)
      ;; FIXME This is highly dependent on the events implementation
      (update-in [:runner :events] config/group-keys :client)))
