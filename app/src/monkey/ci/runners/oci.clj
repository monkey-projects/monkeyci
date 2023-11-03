(ns monkey.ci.runners.oci
  (:require [manifold.deferred :as md]
            [monkey.ci
             [config :as config]
             [runners :as r]]
            [monkey.oci.container-instance.core :as ci]))

(defn- container-config [conf ctx]
  {:display-name "build"
   :image-url (str (:image-url conf) ":" (config/version))})

(defn instance-config
  "Creates container instance configuration using the context"
  [conf ctx]
  (-> conf
      (select-keys [:availability-domain :compartment-id :image-pull-secrets :vnics])
      (assoc :container-restart-policy "NEVER"
             :display-name (get-in ctx [:build :build-id])
             :shape "CI.Standard.A1.Flex"
             :shape-config {:ocpus 1
                            :memory-in-g-b-s 1}
             ;; Assign a checkout volume where the repo is checked out
             :volumes [{:name "checkout"
                        :volume-type "EMPTYDIR"}]
             :containers [(container-config conf ctx)])))

(defn oci-runner [client conf ctx]
  @(md/chain
    (ci/create-container-instance
     client
     {:container-instance (instance-config conf ctx)})
    (constantly 0)))

(defmethod r/make-runner :oci [conf]
  (let [client (ci/make-context (config/->oci-config conf))]
    (partial oci-runner client conf)))
