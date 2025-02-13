(ns monkey.ci.runners.oci3
  "Another implementation of a job runner that uses OCI container instances.
   This one uses mailman-style events instead of manifold.  This should make
   it more robust and better suited for multiple replicas.  Instead of waiting
   for a container instance to complete, we just register multiple event 
   handlers that follow the flow."
  (:require [clojure.tools.logging :as log]
            [io.pedestal.interceptor.chain :as pi]
            [manifold.deferred :as md]
            [monkey.ci
             [build :as b]
             [oci :as oci]
             [storage :as st]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.runners.oci2 :as oci2]
            [monkey.mailman
             [core :as mmc]
             [interceptors :as mmi]]
            [monkey.oci.container-instance.core :as ci]))

(def get-ci-config ::ci-config)

(defn set-ci-config [ctx bi]
  (assoc ctx ::ci-config bi))

(def get-ci-response ::ci-response)

(defn set-ci-response [ctx bi]
  (assoc ctx ::ci-response bi))

(def get-runner-details ::runner-details)

(defn set-runner-details [ctx bi]
  (assoc ctx ::runner-details bi))

(def evt-build (comp :build :event))

#_(defn ci-base-config
  "Interceptor that adds basic container configuration to the context using parameters
   specified in the app config."
  [config]
  {:name ::instance-config
   :enter (fn [ctx]
            (set-ci-config ctx (oci/instance-config config)))})

(defn prepare-ci-config [config]
  "Creates the ci config to run the required containers for the build."
  {:name ::instance-config
   :enter (fn [ctx]
            (set-ci-config ctx (oci2/instance-config config (evt-build ctx))))})

(defn- create-instance [client config]
  (oci/with-retry
    #(ci/create-container-instance client {:container-instance config})))

(defn start-ci [client]
  "Interceptor that starts container instance using the config specified in the context"
  {:name ::start-ci
   :enter (fn [ctx]
            ;; Start container instance, put result back in the context
            ;; TODO Async processing?
            (->> @(create-instance client (get-ci-config ctx))
                 (set-ci-response ctx)))})

(def end-on-ci-failure
  {:name ::end-on-ci-failure
   :enter (fn [ctx]
            (let [resp (get-ci-response ctx)
                  build (evt-build ctx)]
              (cond-> ctx
                (>= (:status resp) 400)
                (-> (assoc :result (b/build-end-evt
                                    (assoc build :message "Failed to create container instance")))
                    ;; Do not proceed
                    (pi/terminate)))))})

(def save-runner-details
  "Interceptor that stores build runner details for oci, such as container instance ocid.
   This assumes the db is present in the context."
  {:name ::save-runner-details
   :enter (fn [ctx]
            (let [sid (get-in ctx [:event :sid])
                  details {:runner :oci
                           :details {:instance-id (get-in (get-ci-response ctx) [:body :id])}}]
              (log/debug "Saving runner details:" details)
              (st/save-runner-details (em/get-db ctx) sid details)
              ctx))})

(def load-runner-details
  "Interceptor that fetches build runner details from the db.
   This assumes the db is present in the context."
  {:name ::load-runner-details
   :enter (fn [ctx]
            (set-runner-details ctx (st/find-runner-details (em/get-db ctx) (get-in ctx [:event :sid]))))})

(defn initialize-build [ctx]
  (b/build-init-evt (get-in ctx [:event :build])))

(defn delete-instance
  "Deletes the container instance associated with the build"
  [client ctx]
  (if-let [instance-id (-> ctx (get-runner-details) :details :instance-id)]
    @(md/chain
      (oci/with-retry #(ci/delete-container-instance client {:instance-id instance-id}))
      (fn [res]
        (if (= 200 (:status res))
          (log/info "Container instance" instance-id "has been deleted")
          (log/warn "Unable to delete container instance" instance-id ", got status" (:status res)))))
    (log/warn "Unable to delete container instance, no instance id in context")))

(defn- make-ci-context [conf]
  (-> (ci/make-context conf)
      (oci/add-inv-interceptor :runners)))

(defn make-routes
  "Creates event handling routes for the given oci configuration"
  [conf]
  (let [client (make-ci-context conf)]
    ;; TODO Timeout handling
    [[:build/pending
      [{:handler initialize-build
        :interceptors [(prepare-ci-config conf)
                       (start-ci client)
                       save-runner-details
                       end-on-ci-failure]}]]

     [:build/end
      [{:handler (partial delete-instance client)
        :interceptors [load-runner-details]}]]]))

(defn make-router [conf storage]
  (mmc/router (make-routes conf)
              {:interceptors [(em/use-db storage)
                              (mmi/sanitize-result)]}))
