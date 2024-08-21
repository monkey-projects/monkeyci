(ns monkey.ci.oci
  "Oracle cloud specific functionality"
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [time :as mt]]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [utils :as u]]
            [monkey.ci.common.preds :as cp]
            [monkey.oci.container-instance.core :as ci]
            [monkey.oci.os
             [martian :as os]
             [stream :as s]]))

(defn stream-to-bucket
  "Pipes an input stream to a bucket object using multipart uploads.
   Returns a deferred that will resolve when the upload completes.
   That is, when the input stream closes, or an error occurs."
  [conf ^java.io.InputStream in]
  (log/debug "Piping stream to bucket" (:bucket-name conf) "at" (:object-name conf))
  (log/trace "Piping stream to bucket using config" conf)
  (-> conf
      (os/make-context)
      (s/input-stream->multipart (assoc conf :input-stream in))))

(def terminated? #{"INACTIVE" "DELETED" "FAILED"})

(defn poll-for-completion
  "Starts an async poll loop that waits until the container instance has completed.
   Returns a deferred that holds the last response received."
  [{:keys [get-details poll-interval post-event instance-id] :as c
    :or {poll-interval 10000
         post-event (constantly true)}}]
  (let [container-failed? (comp (partial some (comp (every-pred number? (complement zero?)) :exit-code))
                                :containers)]
    (log/debug "Starting polling until container instance" instance-id "has exited")
    (md/loop [state nil]
      (md/chain
       (get-details instance-id)
       (fn [{:keys [body] :as r}]
         (let [new-state (:lifecycle-state body)
               failed? (container-failed? body)]
           (when (not= state new-state)
             (log/debug "State change:" state "->" new-state)
             (post-event {:type :oci-container/state-change
                          :details (:body r)}))
           (when failed?
             (log/debug "One of the containers has a nonzero exit code:" (:containers body)))
           (if (or (terminated? new-state) failed?)
             r
             ;; Wait and re-check
             (mt/in poll-interval #(md/recur new-state)))))))))

(defn get-full-instance-details
  "Retrieves full container instance details by retrieving the container instance 
   information, and fetching container details as well."
  [client id]
  (log/trace "Retrieving container instance details for" id)
  (md/chain
   (ci/get-container-instance client {:instance-id id})
   ;; TODO Handle error responses
   (fn [{:keys [status body] :as r}]
     (if (>= status 400)
       (do
         (log/warn "Got error response:" status ", message:" (:message body))
         [r []])
       ;; Fetch details for all containers
       (->> body
            :containers
            (map #(select-keys % [:container-id]))
            (map (partial ci/get-container client))
            (apply md/zip)
            (md/zip r))))
   (fn [[r containers]]
     ;; The exited? option
     ;; Add the exit codes for all containers to the result
     (log/trace "Container details:" containers)
     (let [by-id (->> containers
                      (map :body) ; TODO Check for error response
                      (group-by :id)
                      (mc/map-vals first))
           add-exit-code (fn [c]
                           (merge c (-> (get by-id (:container-id c))
                                        (dissoc :id))))]
       (mc/update-existing-in r [:body :containers] (partial map add-exit-code))))))

(defn run-instance
  "Creates and starts a container instance using the given config, and then
   waits for it to terminate.  Returns a deferred that will hold the full container
   instance state on completion, including the container details.  

   The `exited?` option should be a function that accepts the instance id and returns 
   a deferred with the container instance status when it exits.  If not provided, a 
   basic polling loop will be used.  Not that using extensive polling may lead to 429 
   errors from OCI."
  [client instance-config & [{:keys [delete? exited?] :as opts}]]
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

          (wait-for-exit [{:keys [id]}]
            (if exited?
              (exited? id)
              (poll-for-completion (-> {:instance-id id
                                        :get-details (partial get-full-instance-details client)}
                                       (merge (select-keys opts [:poll-interval :post-event]))))))

          (try-fetch-logs [{:keys [body] :as r}]
            ;; Try to download the outputs for each of the containers.  Since we can
            ;; only do this if the instance is still running, check for lifecycle state.
            (if (= "ACTIVE" (:lifecycle-state body))
              (do
                (log/debug "Instance" (:display-name body) "is still running, so fetching logs")
                (md/chain
                 (->> (:containers body)
                      (map (fn [c]
                             (md/chain
                              (ci/retrieve-logs client (select-keys c [:container-id]))
                              #(mc/assoc-some c :logs (:body %)))))
                      (apply md/zip))
                 (partial assoc-in r [:body :containers])))
              r))

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
         (check-error wait-for-exit)
         try-fetch-logs
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

(def default-cpu-count 1)
(def default-memory-gb 2)

(defn instance-config
  "Generates a skeleton instance configuration, generated from the oci configuration."
  [conf]
  (-> conf
      (select-keys [:compartment-id :image-pull-secrets :vnics :freeform-tags])
      (assoc :container-restart-policy "NEVER"
             :shape "CI.Standard.A1.Flex" ; Use ARM shape, it's cheaper
             :shape-config {:ocpus default-cpu-count
                            :memory-in-g-bs default-memory-gb}
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
  (mc/find-first (cp/prop-pred :volume-name n)
                 (:volume-mounts c)))

(defn find-volume
  "Finds volume with given name in the container instance"
  [ci n]
  (mc/find-first (cp/prop-pred :name n)
                 (:volumes ci)))

(defn config-entry
  "Creates an entry config for a volume, where the contents are base64 encoded."
  [n v]
  {:file-name n
   :data (u/->base64 v)})

(defn checkout-subdir
  "Returns the path for `n` as a subdir of the checkout dir"
  [n]
  (str checkout-dir "/" n))

(def work-dir (checkout-subdir "work"))

(defn base-work-dir
  "Determines the base work dir to use inside the container"
  [rt]
  (some->> (b/rt->checkout-dir rt)
           (fs/file-name)
           (fs/path work-dir)
           (str)))
