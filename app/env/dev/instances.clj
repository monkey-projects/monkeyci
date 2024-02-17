(ns instances
  (:require [clojure.tools.logging :as log]
            [config :as co]
            [monkey.ci.containers :as c]
            [monkey.ci.containers.oci :as oci-cont]
            [monkey.ci.oci :as oci]
            [monkey.ci.runners.oci :as ro]
            [monkey.oci.container-instance.core :as ci]
            [manifold.deferred :as md]))

(defn run-test-container []
  (let [conf (co/oci-runner-config)
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
  (let [conf (co/oci-runner-config)
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
  (let [conf (co/oci-runner-config)
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
  []
  (let [rt (-> @co/global-config
               (assoc :step
                      {:container/image "docker.io/alpine:latest"
                       :script ["echo 'Hi, this is a simple test'"]}
                      :build
                      {:build-id (str "test-build-" (System/currentTimeMillis))
                       :pipeline "test-pipe"
                       :index "0"}))]
    (c/run-container rt)))
