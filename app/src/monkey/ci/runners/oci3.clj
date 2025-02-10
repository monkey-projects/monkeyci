(ns monkey.ci.runners.oci3
  "Another implementation of a job runner that uses OCI container instances.
   This one uses mailman-style events instead of manifold.  This should make
   it more robust and better suited for multiple replicas.  Instead of waiting
   for a container instance to complete, we just register multiple event 
   handlers that follow the flow."
  (:require [clojure.tools.logging :as log]
            [io.pedestal.interceptor.chain :as pi]
            [monkey.ci
             [build :as b]
             [oci :as oci]]
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

(defn ci-base-config
  "Interceptor that adds basic container configuration to the context using parameters
   specified in the app config."
  [config]
  {:name ::instance-config
   :enter (fn [ctx]
            (set-ci-config ctx (oci/instance-config config)))})

(defn start-build
  "Starts a build by creating a container instance with a configuration as
   specified by the oci2 runner."
  [conf build]
  (let [ic (oci2/instance-config conf build)]
    ))

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
                  build (get-in ctx [:event :build])]
              (cond-> ctx
                (>= (:status resp) 400)
                (-> (assoc :result (b/build-end-evt
                                    (assoc build :message "Failed to create container instance")))
                    ;; Do not proceed
                    (pi/terminate)))))})

(defn initialize-build [ctx]
  (b/build-init-evt (get-in ctx [:event :build])))

(defn delete-instance
  "Deletes the container instance associated with the build"
  [ctx]
  ;; TODO Find the instance id for the build.  Ideally, this is stored in the build.
  ;; otherwise we'll have to look it up.
  )

(defn- make-ci-context [conf]
  (-> (ci/make-context conf)
      (oci/add-inv-interceptor :runners)))

(defn make-routes [conf]
  (let [client (make-ci-context conf)]
    [[:build/pending
      [{:handler initialize-build
        :interceptors [(ci-base-config conf)
                       (start-ci client)
                       end-on-ci-failure]}]]

     [:build/end
      [{:handler delete-instance}]]]))

(defn make-router [conf]
  (mmc/router (make-routes conf)
              {:interceptors [(mmi/sanitize-result)]}))
