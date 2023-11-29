(ns monkey.ci.oci
  "Oracle cloud specific functionality"
  (:require [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [time :as mt]]
            [medley.core :as mc]
            [monkey.ci.utils :as u]
            [monkey.oci.container-instance.core :as ci]
            [monkey.oci.os
             [martian :as os]
             [stream :as s]]))

(defn ->oci-config
  "Given a configuration map with credentials, turns it into a config map
   that can be passed to OCI context creators."
  [{:keys [credentials] :as conf}]
  (-> conf
      (merge (mc/update-existing credentials :private-key u/load-privkey))
      (dissoc :credentials)))

(defn ctx->oci-config
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
