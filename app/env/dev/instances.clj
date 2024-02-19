(ns instances
  (:require [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [config :as co]
            [monkey.ci
             [config :as config]
             [containers :as c]
             [oci :as oci]
             [runtime :as rt]]
            [monkey.ci.containers.oci :as oci-cont]
            [monkey.ci.runners.oci :as ro]
            [monkey.oci.container-instance.core :as ci]
            [manifold.deferred :as md]))

(defn run-test-container []
  (let [conf (co/oci-container-config)
        client (ci/make-context conf)
        ic (-> (ro/instance-config conf {:build {:build-id "test-build"}})
               (assoc :containers [{:image-url "fra.ocir.io/frjdhmocn5qi/monkeyci:0.1.0"
                                    :display-name "test"
                                    :arguments ["-h"]}])
               (dissoc :volumes))]
    (log/info "Running instance with config:" ic)
    (oci/run-instance client ic)))

(defn print-container-logs [client {{:keys [lifecycle-state] :as s} :details}]
  (println "Got state change:" lifecycle-state)
  (when (= "ACTIVE" lifecycle-state)
    (let [cid (-> s :containers first :container-id)]
      (println "Retrieving container logs for" cid)
      (md/chain
       (ci/retrieve-logs client {:container-id cid})
       :body
       println))))

(defn pinp-test
  "Trying to get podman-in-podman to run on a container instance."
  []
  (let [conf (co/oci-container-config)
        client (ci/make-context conf)
        ic (-> (ro/instance-config conf {:build {:build-id "podman-in-container"}})
               (assoc :containers [{:image-url "fra.ocir.io/frjdhmocn5qi/pinp:latest"
                                    :display-name "pinp"
                                    :arguments ["podman" "info"]
                                    :security-context
                                    {:security-context-type "LINUX"
                                     :run-as-user 1000}}])
               (dissoc :volumes))]
    (oci/run-instance client ic (partial print-container-logs client))))

(defn delete-container-instance
  "Deletes container instance with given name"
  [n]
  (let [conf (co/oci-container-config)
        client (ci/make-context conf)]
    (md/chain
     (ci/list-container-instances client (select-keys conf [:compartment-id]))
     :body
     :items
     (partial filter (fn [{:keys [lifecycle-state display-name]}]
                       (and (not (contains? #{"DELETING" "DELETED"} lifecycle-state))
                            (= n display-name))))
     first
     :id
     #(ci/delete-container-instance client {:instance-id %}))))

(defn run-container-with-sidecar
  "Runs a container in OCI that deploys a sidecar"
  [& [version]]
  (let [rt (-> @co/global-config
               (config/normalize-config {} {})
               (assoc-in [:containers :image-tag] (or version "latest"))
               (rt/config->runtime)
               (assoc :step
                      {:container/image "docker.io/alpine:latest"
                       :script ["echo 'Hi, this is a simple test'"]}
                      :build
                      {:build-id (str "test-build-" (System/currentTimeMillis))
                       :pipeline "test-pipe"
                       :index "0"}))]
    (md/future (c/run-container rt))))

(defn- instance-call
  ([f conf-fn res-fn]
   (let [conf (co/oci-container-config)
         client (ci/make-context conf)]
     (md/chain
      (f client (conf-fn conf))
      :body
      res-fn)))
  ([f conf-fn]
   (instance-call f conf-fn identity)))

(defn list-instances []
  (instance-call ci/list-container-instances #(select-keys % [:compartment-id]) :items))

(defn get-instance [id]
  (instance-call ci/get-container-instance (constantly {:instance-id id})))

(defn get-container [id]
  (instance-call ci/get-container (constantly {:container-id id})))
