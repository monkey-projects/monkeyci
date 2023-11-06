(ns monkey.ci.runners.oci
  (:require [clojure.core.async :as ca :refer [<!]]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [config :as config]
             [runners :as r]
             [utils :as u]]
            [monkey.oci.container-instance.core :as ci]))

(def checkout-vol "checkout")

(defn- format-sid [sid]
  (cs/join "/" sid))

(defn- container-config [conf ctx]
  (let [checkout "/opt/monkeyci/checkout"]
    {:display-name "build"
     ;; The image url must point to a container running monkeyci cli
     :image-url (str (:image-url conf) ":" (config/version))
     :arguments ["-w" checkout "build" "--sid" (format-sid (get-in ctx [:build :sid]))]
     :volume-mounts [{:mount-path checkout
                      :is-read-only false
                      :volume-name checkout-vol}]}))

(defn instance-config
  "Creates container instance configuration using the context"
  [conf ctx]
  (-> conf
      (select-keys [:availability-domain :compartment-id :image-pull-secrets :vnics])
      (assoc :container-restart-policy "NEVER"
             :display-name (get-in ctx [:build :build-id])
             :shape "CI.Standard.A1.Flex" ; Use ARM shape, it's cheaper
             :shape-config {:ocpus 1
                            :memory-in-g-b-s 1}
             ;; Assign a checkout volume where the repo is checked out
             :volumes [{:name checkout-vol
                        :volume-type "EMPTYDIR"}]
             :containers [(container-config conf ctx)])))

(defn wait-for-completion
  "Starts an async poll loop that waits until the container instance has completed."
  [client {:keys [get-details poll-interval] :as c :or {poll-interval 5000}}]
  (let [get-async (fn []
                    (u/future->ch (get-details client (select-keys c [:instance-id]))))
        done? #{"INACTIVE" "DELETED" "FAILED"}]
    (ca/go-loop [state nil
                 p (get-async)]
      (let [r (<! p) ; Wait until the info has arrived
            new-state (get-in r [:body :lifecycle-state])]
        (when (not= state new-state)
          (log/debug "State change:" state "->" new-state))
        (if (done? new-state)
          (if (= "INACTIVE" new-state) 0 1)
          (do
            ;; Wait and re-check
            (<! (ca/timeout poll-interval))
            (recur new-state (get-async))))))))

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
            ;; TODO Replace this with OCI events as soon as they become available
            (wait-for-completion client {:instance-id id
                                         :get-details ci/get-container-instance}))

          (return-result [ch]
            ;; Either return the incoming channel, or nonzero in case of error
            (or ch (ca/to-chan! [1])))]
    
    @(md/chain
      (create-instance)
      (check-error start-polling)
      return-result)))

(defn oci-runner [client conf ctx]
  (run-instance client (instance-config conf ctx)))

(defmethod r/make-runner :oci [conf]
  (let [client (ci/make-context (config/->oci-config conf))]
    (partial oci-runner client conf)))
