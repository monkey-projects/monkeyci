(ns monkey.ci.runners.oci3
  "Another implementation of a job runner that uses OCI container instances.
   This one uses mailman-style events instead of manifold.  This should make
   it more robust and better suited for multiple replicas.  Instead of waiting
   for a container instance to complete, we just register multiple event 
   handlers that follow the flow."
  (:require [monkey.ci.oci :as oci]
            [monkey.ci.runners.oci2 :as oci2]
            [monkey.mailman.core :as mmc]
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

(defn start-ci [client]
  "Interceptor that starts container instance using the config specified in the context"
  {:name ::start-ci
   :enter (fn [ctx]
            ;; Start container instance, put result back in the context
            (->> @(ci/create-container-instance client (get-ci-config ctx))
                 (set-ci-response ctx)))})

(defn start-instance [ctx]
  ;; TODO
  )

(defn delete-instance
  "Deletes the container instance associated with the build"
  [ctx])

(defn- make-ci-context [conf]
  (-> (ci/make-context conf)
      (oci/add-inv-interceptor :runners)))

(defn make-routes [conf]
  (let [client (make-ci-context conf)]
    [[:build/pending
      {:handler start-instance
       :interceptors [(ci-base-config conf)
                      (start-ci client)]}]

     [:build/end
      {:handler delete-instance}]]))

