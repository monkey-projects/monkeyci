(ns monkey.ci.oci
  "Oracle cloud specific functionality"
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [time :as mt]]
            [medley.core :as mc]
            [monkey.ci
             [config :as c]
             [utils :as u]]
            [monkey.oci.container-instance.core :as ci]
            [monkey.oci.os
             [martian :as os]
             [stream :as s]]))

(defn group-credentials
  "Assuming the conf is taken from env, groups all keys that start with `credentials-`
   into the `:credentials` submap."
  [conf]
  (c/group-and-merge conf :credentials))

(defn normalize-config
  "Normalizes the given OCI config key, by grouping the credentials both in the given key
   and in the `oci` key, and merging them."
  [conf k]
  (letfn [(load-key [c]
            (mc/update-existing-in c [:credentials :private-key] u/load-privkey))]
    (->> [(:oci (c/group-and-merge conf :oci)) (k conf)]
         (map group-credentials)
         (apply merge)
         (load-key)
         (assoc conf k))))

(defn ->oci-config
  "Given a configuration map with credentials, turns it into a config map
   that can be passed to OCI context creators."
  [{:keys [credentials] :as conf}]
  (-> conf
      ;; TODO Remove this, it's already done when normalizing the config
      (merge (mc/update-existing credentials :private-key u/load-privkey))
      (dissoc :credentials)))

(defn ^:deprecated ctx->oci-config
  "Gets the oci configuration for the given key from the context.  This merges
   in the general OCI configurationn."
  [ctx k]
  (u/deep-merge (:oci ctx)
                (k ctx)))

(defn stream-to-bucket
  "Pipes an input stream to a bucket object using multipart uploads.
   Returns a deferred that will resolve when the upload completes.
   That is, when the input stream closes, or an error occurs."
  [conf ^java.io.InputStream in]
  (log/debug "Piping stream to bucket using config" conf)
  (let [ctx (-> conf
                (->oci-config)
                (os/make-context))]
    (s/input-stream->multipart ctx (assoc conf :input-stream in))))

(defn wait-for-completion
  "Starts an async poll loop that waits until the container instance has completed.
   Returns a deferred that holds the last response received."
  [client {:keys [get-details poll-interval post-event] :as c
           :or {poll-interval 5000
                post-event (constantly true)}}]
  (let [get-async (fn []
                    (get-details client (select-keys c [:instance-id])))
        done? #{"INACTIVE" "DELETED" "FAILED"}]
    (md/loop [state nil]
      (md/chain
       (get-async)
       (fn [r]
         (let [new-state (get-in r [:body :lifecycle-state])]
           (when (not= state new-state)
             (log/debug "State change:" state "->" new-state)
             (post-event {:type :oci-container/state-change
                          :details (:body r)}))
           (if (done? new-state)
             r
             ;; Wait and re-check
             (mt/in poll-interval #(md/recur new-state)))))))))

(defn run-instance
  "Creates and starts a container instance using the given config, and then
   waits for it to terminate.  Returns a deferred that will hold the exit value."
  [client instance-config & [post-event]]
  (log/debug "Running OCI instance with config:" instance-config)
  (letfn [(check-error [handler]
            (fn [{:keys [body status]}]
              (when status
                (if (>= status 400)
                  (log/warn "Got an error response, status" status "with message" (:message body))
                  (handler body)))))
          
          (create-instance []
            (log/debug "Creating instance...")
            (ci/create-container-instance
             client
             {:container-instance instance-config}))
          
          (start-polling [{:keys [id]}]
            (log/debug "Starting polling...")
            ;; TODO Replace this with OCI events as soon as they become available.
            ;; TODO Don't wait, just let the container fire an event and react to that.
            (wait-for-completion client
                                 (-> {:instance-id id
                                      :get-details ci/get-container-instance}
                                     (mc/assoc-some :post-event post-event))))

          (get-container-exit [r]
            (let [cid (-> (:containers r)
                          first
                          :container-id)]
              (ci/get-container
               client
               {:container-id cid})))

          (return-result [{{:keys [exit-code]} :body}]
            ;; Return the exit code, or nonzero when no code is specified
            (or exit-code 1))

          (log-error [ex]
            (log/error "Error creating container instance" ex)
            ;; Nonzero exit code
            1)]
    
    (-> (md/chain
         (create-instance)
         (check-error start-polling)
         (check-error get-container-exit)
         return-result)
        (md/catch log-error))))

(defn- privkey-base64
  "Finds the private key referenced in the config, reads it and
   returns it as a base64 encoded string."
  [conf]
  ;; TODO Allow for a private key to be specified other than the one
  ;; used by the app itself.  Perhaps fetch it from the vault?
  (let [f (fs/file (get-in conf [:credentials :private-key]))]
    (when (fs/exists? f)
      (u/->base64 (slurp f)))))

(def checkout-vol "checkout")
(def checkout-dir "/opt/monkeyci/checkout")
(def privkey-vol "private-key")
(def privkey-file "privkey")
(def key-dir "/opt/monkeyci/keys")

(defn- container-config [conf pk]
  ;; The image url must point to a container running monkeyci cli
  {:image-url (str (:image-url conf) ":" (:image-tag conf))
   :volume-mounts (cond-> [{:mount-path checkout-dir
                            :is-read-only false
                            :volume-name checkout-vol}]
                    pk (conj {:mount-path key-dir
                              :is-read-only true
                              :volume-name privkey-vol}))})

(defn instance-config
  "Generates a skeleton instance configuration, generated from the oci configuration.
   This includes a config volume that holds the private key to access other oci 
   services."
  [conf]
  (let [pk (privkey-base64 conf)]
    (-> conf
        (select-keys [:availability-domain :compartment-id :image-pull-secrets :vnics])
        (assoc :container-restart-policy "NEVER"
               :shape "CI.Standard.A1.Flex" ; Use ARM shape, it's cheaper
               :shape-config {:ocpus 1
                              :memory-in-g-bs 2}
               :volumes (cond-> [{:name checkout-vol
                                  :volume-type "EMPTYDIR"
                                  :backing-store "EPHEMERAL_STORAGE"}]
                          pk (conj {:name privkey-vol
                                    :volume-type "CONFIGFILE"
                                    :configs [{:file-name privkey-file
                                               :data pk}]}))
               :containers [(container-config conf pk)]))))

(defn sid->tags [sid]
  (->> sid
       (remove nil?)
       (zipmap ["customer-id" "project-id" "repo-id"])))

(defn find-mount
  "Finds mount with given volume name in the container"
  [c n]
  (mc/find-first (u/prop-pred :volume-name n)
                 (:volume-mounts c)))
