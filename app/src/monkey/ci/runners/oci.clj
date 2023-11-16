(ns monkey.ci.runners.oci
  (:require [clojure.core.async :as ca :refer [<!]]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [config :as config]
             [context :as ctx]
             [oci :as oci]
             [runners :as r]
             [utils :as u]]
            [monkey.oci.container-instance.core :as ci]))

(def checkout-vol "checkout")
(def checkout-dir "/opt/monkeyci/checkout")

(def format-sid (partial cs/join "/"))

(defn- ->env [ctx]
  (->> (ctx/ctx->env ctx)
       (config/config->env)
       (mc/map-keys name)))

(defn- container-config [conf ctx]
  (let [git (get-in ctx [:build :git])]
    {:display-name "build"
     ;; The image url must point to a container running monkeyci cli
     :image-url (str (:image-url conf) ":" (config/version))
     :arguments (cond-> ["-w" checkout-dir "build" "run"
                         "--sid" (format-sid (get-in ctx [:build :sid]))]
                  (not-empty git) (concat ["-u" (:url git)
                                           "-b" (:branch git)
                                           "--commit-id" (:id git)]))
     :environment-variables (->env ctx)
     :volume-mounts [{:mount-path checkout-dir
                      :is-read-only false
                      :volume-name checkout-vol}]}))

(defn instance-config
  "Creates container instance configuration using the context"
  [conf ctx]
  (let [tags (->> (get-in ctx [:build :sid])
                  (remove nil?)
                  (zipmap ["customer-id" "project-id" "repo-id"]))]
    (-> conf
        (select-keys [:availability-domain :compartment-id :image-pull-secrets :vnics])
        (assoc :container-restart-policy "NEVER"
               :display-name (get-in ctx [:build :build-id])
               :shape "CI.Standard.A1.Flex" ; Use ARM shape, it's cheaper
               :shape-config {:ocpus 1
                              :memory-in-g-bs 2}
               ;; Assign a checkout volume where the repo is checked out.
               ;; This will be the working dir.
               :volumes [{:name checkout-vol
                          :volume-type "EMPTYDIR"
                          :backing-store "EPHEMERAL_STORAGE"}]
               :containers [(container-config conf ctx)]
               :freeform-tags tags))))

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
          ;; TODO Fire event instead
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

(defmethod r/make-runner :oci [ctx]
  (let [conf (oci/ctx->oci-config ctx :runner)
        client (-> conf
                   (oci/->oci-config)
                   (ci/make-context))]
    (partial oci-runner client conf)))
