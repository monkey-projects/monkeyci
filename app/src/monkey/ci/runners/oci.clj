(ns monkey.ci.runners.oci
  (:require [clojure.string :as cs]
            [manifold.deferred :as md]
            [monkey.ci
             [config :as config]
             [runners :as r]]
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

(defn oci-runner [client conf ctx]
  @(md/chain
    (ci/create-container-instance
     client
     {:container-instance (instance-config conf ctx)})
    (constantly 0)))

(defmethod r/make-runner :oci [conf]
  (let [client (ci/make-context (config/->oci-config conf))]
    (partial oci-runner client conf)))
