(ns monkey.ci.runners.oci
  (:require [babashka.fs :as fs]
            [clojure.core.async :as ca :refer [<!]]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [time :as mt]]
            [medley.core :as mc]
            [monkey.ci
             [config :as config]
             [context :as ctx]
             [events :as e]
             [oci :as oci]
             [runners :as r]
             [utils :as u]]
            [monkey.oci.container-instance.core :as ci]))

(def checkout-vol "checkout")
(def checkout-dir "/opt/monkeyci/checkout")
(def build-container "build")
(def privkey-vol "private-key")
(def privkey-file "privkey")
(def key-dir "/opt/monkeyci/keys")

(def format-sid (partial cs/join "/"))

(defn- ->env [ctx pk]
  (->> (cond-> (ctx/ctx->env ctx)
         ;; FIXME This will turn out wrong if the private key is specified elsewhere
         pk (assoc-in [:oci :credentials :private-key] (str key-dir "/" privkey-file)))
       (config/config->env)
       (mc/map-keys name)))

(defn- container-config [conf ctx pk]
  (let [git (get-in ctx [:build :git])]
    {:display-name build-container
     ;; The image url must point to a container running monkeyci cli
     :image-url (str (:image-url conf) ":" (or (:image-tag conf) (config/version)))
     :arguments (cond-> ["-w" checkout-dir "build" "run"
                         "--sid" (format-sid (get-in ctx [:build :sid]))]
                  (not-empty git) (concat ["-u" (:url git)
                                           "-b" (:branch git)
                                           "--commit-id" (:id git)]))
     :environment-variables (->env ctx pk)
     :volume-mounts (cond-> [{:mount-path checkout-dir
                              :is-read-only false
                              :volume-name checkout-vol}]
                      pk (conj {:mount-path key-dir
                                :is-read-only true
                                :volume-name privkey-vol}))}))

(defn- privkey-base64
  "Finds the private key referenced in the config, reads it and
   returns it as a base64 encoded string."
  [conf]
  ;; TODO Allow for a private key to be specified other than the one
  ;; used by the app itself.  Perhaps fetch it from the vault?
  (let [f (fs/file (get-in conf [:credentials :private-key]))]
    (when (fs/exists? f)
      (u/->base64 (slurp f)))))

(defn instance-config
  "Creates container instance configuration using the context"
  [conf ctx]
  (let [tags (->> (get-in ctx [:build :sid])
                  (remove nil?)
                  (zipmap ["customer-id" "project-id" "repo-id"]))
        pk (privkey-base64 conf)]
    (-> conf
        (select-keys [:availability-domain :compartment-id :image-pull-secrets :vnics])
        (assoc :container-restart-policy "NEVER"
               :display-name (get-in ctx [:build :build-id])
               :shape "CI.Standard.A1.Flex" ; Use ARM shape, it's cheaper
               :shape-config {:ocpus 1
                              :memory-in-g-bs 2}
               ;; Assign a checkout volume where the repo is checked out.
               ;; This will be the working dir.
               :volumes (cond-> [{:name checkout-vol
                                  :volume-type "EMPTYDIR"
                                  :backing-store "EPHEMERAL_STORAGE"}]
                          pk (conj {:name privkey-vol
                                    :volume-type "CONFIGFILE"
                                    :configs [{:file-name privkey-file
                                               :data pk}]}))
               :containers [(container-config conf ctx pk)]
               :freeform-tags tags))))

(defn wait-for-completion
  "Starts an async poll loop that waits until the container instance has completed.
   Returns a deferred that holds the last response received."
  [client {:keys [get-details poll-interval] :as c :or {poll-interval 5000}}]
  (let [get-async (fn []
                    (get-details client (select-keys c [:instance-id])))
        done? #{"INACTIVE" "DELETED" "FAILED"}]
    (md/loop [state nil]
      (md/chain
       (get-async)
       (fn [r]
         (let [new-state (get-in r [:body :lifecycle-state])]
           (when (not= state new-state)
             ;; TODO Fire event instead
             (log/debug "State change:" state "->" new-state))
           (if (done? new-state)
             r
             ;; Wait and re-check
             (mt/in poll-interval #(md/recur new-state)))))))))

(defn run-instance
  "Creates and starts a container instance using the given config, and then
   waits for it to terminate.  Returns a channel that will hold the exit value."
  [client instance-config]
  (letfn [(check-error [handler]
            (fn [{:keys [body status]}]
              (when status
                (if (>= status 400)
                  (log/warn "Got an error response, status" status "with message" (:message body))
                  (handler body)))))
          
          (create-instance []
            (ci/create-container-instance
             client
             {:container-instance instance-config}))
          
          (start-polling [{:keys [id]}]
            ;; TODO Replace this with OCI events as soon as they become available.
            ;; TODO Don't wait, just let the container fire an event and react to that.
            (wait-for-completion client {:instance-id id
                                         :get-details ci/get-container-instance}))

          (get-container-exit [r]
            (let [cid (-> (:containers r)
                          first
                          :container-id)]
              (ci/get-container
               client
               {:container-id cid})))

          (return-result [{{:keys [exit-code]} :body}]
            ;; Return the exit code, or nonzero when no code is specified
            (ca/to-chan! [(or exit-code 1)]))]
    
    @(md/chain
      (create-instance)
      (check-error start-polling)
      (check-error get-container-exit)
      return-result)))

(defn oci-runner [client conf ctx]
  (ca/go
    (-> (ca/<! (run-instance client (instance-config conf ctx)))
        (e/then-fire ctx #(e/build-completed-evt (:build ctx) %)))))

(defmethod r/make-runner :oci [ctx]
  (let [conf (oci/ctx->oci-config ctx :runner)
        client (-> conf
                   (oci/->oci-config)
                   (ci/make-context))]
    (partial oci-runner client conf)))
