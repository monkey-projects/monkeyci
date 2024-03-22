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
            [monkey.oci.container-instance.core :as ci]))

(def build-container "build")
(def format-sid (partial cs/join "/"))
(def ssh-keys-volume "ssh-keys")
(def ssh-keys-dir oci/key-dir)
(def log-config-volume "log-config")
(def log-config-dir "/home/monkeyci/config")

(def build-ssh-keys (comp :ssh-keys :git :build))
(def log-config (comp :log-config :runner :config))

(defn- prepare-config-for-oci [config]
  (-> config
      ;; Enforce child runner
      (assoc :runner {:type :child})))

(defn- add-ssh-keys-dir [rt conf]
  (cond-> conf
    (build-ssh-keys rt) (assoc-in [:build :git :ssh-keys-dir] ssh-keys-dir)))

(defn- add-log-config-dir [rt conf]
  (cond-> conf
    (log-config rt) (assoc-in [:runner :log-config] log-config-dir)))

(defn- ->env [rt]
  (->> (rt/rt->env rt)
       (prepare-config-for-oci)
       (add-ssh-keys-dir rt)
       (add-log-config-dir rt)
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
       :configs (oci/config-entry "logback.xml" (slurp f))}
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
  (let [git (get-in rt [:build :git])]
    [(-> conf
         (assoc :display-name build-container
                :arguments (cond-> ["-w" oci/checkout-dir "build" "run"
                                    "--sid" (format-sid (b/get-sid rt))]
                             (not-empty git) (concat ["-u" (:url git)
                                                      "-b" (:branch git)
                                                      "--commit-id" (:commit-id git)]))
                ;; TODO Log config
                :environment-variables (->env rt))
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
  (* 3600 60 1000))

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
                                     (fn [_]
                                       (oci/get-full-instance-details client id))))})
      (md/chain
       (fn [r]
         (or (-> r :body :containers first :exit-code) 1))
       (fn [r]
         (rt/post-events rt (b/build-end-evt (rt/build rt) r))
         r))
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
  (oci/normalize-config conf :runner))
