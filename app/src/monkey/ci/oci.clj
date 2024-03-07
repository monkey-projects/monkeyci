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
             [pem :as pem]
             [utils :as u]]
            [monkey.oci.container-instance.core :as ci]
            [monkey.oci.os
             [martian :as os]
             [stream :as s]]))

(defn group-credentials
  "Assuming the conf is taken from env, groups all keys that start with `credentials-`
   into the `:credentials` submap."
  [conf]
  (c/group-keys conf :credentials))

(defn normalize-config
  "Normalizes the given OCI config key, by grouping the credentials both in the given key
   and in the `oci` key, and merging them."
  [conf k]
  (letfn [(load-key [c]
            (mc/update-existing-in c [:credentials :private-key] u/load-privkey))]
    (->> [(:oci (c/group-and-merge-from-env conf :oci)) (k conf)]
         (map group-credentials)
         (apply u/deep-merge)
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

(defn stream-to-bucket
  "Pipes an input stream to a bucket object using multipart uploads.
   Returns a deferred that will resolve when the upload completes.
   That is, when the input stream closes, or an error occurs."
  [conf ^java.io.InputStream in]
  (log/debug "Piping stream to bucket" (:bucket-name conf) "at" (:object-name conf))
  (log/trace "Piping stream to bucket using config" conf)
  (let [ctx (-> conf
                (->oci-config)
                (os/make-context))]
    (s/input-stream->multipart ctx (assoc conf :input-stream in))))

(defn wait-for-completion
  "Starts an async poll loop that waits until the container instance has completed.
   Returns a deferred that holds the last response received."
  [{:keys [get-details poll-interval post-event] :as c
    :or {poll-interval 5000
         post-event (constantly true)}}]
  (let [get-async (fn []
                    (get-details (:instance-id c)))
        done? #{"INACTIVE" "DELETED" "FAILED"}
        container-failed? (comp (partial some (comp (every-pred number? (complement zero?)) :exit-code))
                                :containers)]
    (md/loop [state nil]
      (md/chain
       (get-async)
       (fn [{:keys [body] :as r}]
         (let [new-state (:lifecycle-state body)
               failed? (container-failed? body)]
           (when (not= state new-state)
             (log/debug "State change:" state "->" new-state)
             (post-event {:type :oci-container/state-change
                          :details (:body r)}))
           (when failed?
             (log/debug "One of the containers has a nonzero exit code:" (:containers body)))
           (if (or (done? new-state) failed?)
             r
             ;; Wait and re-check
             (mt/in poll-interval #(md/recur new-state)))))))))

(defn run-instance
  "Creates and starts a container instance using the given config, and then
   waits for it to terminate.  Returns a deferred that will hold the full container
   instance state on completion, including the container details."
  [client instance-config & [{:keys [delete?] :as opts}]]
  (log/debug "Running OCI instance with config:" instance-config)
  (letfn [(check-error [handler]
            (fn [{:keys [body status] :as r}]
              (if status
                (if (>= status 400)
                  (do
                    (log/warn "Got an error response, status" status "with message" (:message body))
                    r)
                  (handler body))
                ;; Some other invalid response
                r)))
          
          (create-instance []
            (log/debug "Creating instance...")
            (ci/create-container-instance
             client
             {:container-instance instance-config}))

          (get-instance-details [id]
            (log/trace "Retrieving container instance details for" id)
            (md/chain
             (ci/get-container-instance client {:instance-id id})
             (fn [{:keys [status body] :as r}]
               ;; Fetch details for all containers
               (->> body
                    :containers
                    (map #(select-keys % [:container-id]))
                    (map (partial ci/get-container client))
                    (apply md/zip)
                    (md/zip r)))
             (fn [[r containers]]
               ;; Add the exit codes for all containers to the result
               (log/trace "Container details:" containers)
               (let [by-id (->> containers
                                (map :body) ; TODO Check for error response
                                (group-by :id)
                                (mc/map-vals first))
                     add-exit-code (fn [c]
                                     (merge c (-> (get by-id (:container-id c))
                                                  (dissoc :id))))]
                 (update-in r [:body :containers] (partial map add-exit-code))))))
          
          (start-polling [{:keys [id]}]
            (log/debug "Starting polling...")
            ;; TODO Replace this with OCI events as soon as they become available.
            (wait-for-completion (-> {:instance-id id
                                      :get-details get-instance-details}
                                     (merge (select-keys opts [:poll-interval :post-event])))))

          (maybe-delete-instance [{{:keys [id]} :body :as c}]
            (if (and delete? id)
              (md/chain
               (ci/delete-container-instance client {:instance-id id})
               (constantly c))
              (md/success-deferred c)))

          (log-error [ex]
            (log/error "Error creating container instance" ex)
            ;; Return the exception
            {:status 500
             :exception ex})]
    
    (-> (md/chain
         (create-instance)
         (check-error start-polling)
         maybe-delete-instance)
        (md/catch log-error))))

(def checkout-vol "checkout")
(def checkout-dir "/opt/monkeyci/checkout")
(def key-dir "/opt/monkeyci/keys")

(defn- container-config [conf]
  ;; The image url must point to a container running monkeyci cli
  {:image-url (str (:image-url conf) ":" (:image-tag conf))
   :volume-mounts [{:mount-path checkout-dir
                    :is-read-only false
                    :volume-name checkout-vol}]})

(defn- pick-ad
  "Either returns the configured AD, or picks one from the list if multiple specified."
  [{ads :availability-domains ad :availability-domain}]
  (or ad (when ads (nth (vec ads) (rand-int (count ads))))))

(defn instance-config
  "Generates a skeleton instance configuration, generated from the oci configuration."
  [conf]
  (-> conf
      (select-keys [:compartment-id :image-pull-secrets :vnics :freeform-tags])
      (assoc :container-restart-policy "NEVER"
             :shape "CI.Standard.A1.Flex" ; Use ARM shape, it's cheaper
             :shape-config {:ocpus 1
                            :memory-in-g-bs 2}
             :availability-domain (pick-ad conf)
             :volumes [{:name checkout-vol
                        :volume-type "EMPTYDIR"
                        :backing-store "EPHEMERAL_STORAGE"}]
             :containers [(container-config conf)])))

(defn sid->tags [sid]
  (->> sid
       (remove nil?)
       (zipmap ["customer-id" "repo-id"])))

(defn find-mount
  "Finds mount with given volume name in the container"
  [c n]
  (mc/find-first (u/prop-pred :volume-name n)
                 (:volume-mounts c)))

(defn find-volume
  "Finds volume with given name in the container instance"
  [ci n]
  (mc/find-first (u/prop-pred :name n)
                 (:volumes ci)))
