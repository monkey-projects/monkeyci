(ns monkey.ci.runners.oci
  (:require [babashka.fs :as fs]
            [clojure.core.async :as ca :refer [<!]]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [config :as config]
             [context :as ctx]
             [events :as e]
             [oci :as oci]
             [runners :as r]
             [utils :as u]]
            [monkey.oci.container-instance.core :as ci]))

(def build-container "build")
(def format-sid (partial cs/join "/"))

(defn- ->env [ctx pk?]
  (->> (cond-> (ctx/ctx->env ctx)
         ;; FIXME This will turn out wrong if the private key is specified elsewhere
         ;; TODO Move this to oci ns
         pk? (assoc-in [:oci :credentials :private-key] (str oci/key-dir "/" oci/privkey-file)))
       (config/config->env)
       (mc/map-keys name)
       (mc/remove-vals empty?)))

(defn- patch-container [[conf] ctx pk?]
  (let [git (get-in ctx [:build :git])]
    [(assoc conf
            :display-name build-container
            :arguments (cond-> ["-w" oci/checkout-dir "build" "run"
                                "--sid" (format-sid (get-in ctx [:build :sid]))]
                         (not-empty git) (concat ["-u" (:url git)
                                                  "-b" (:branch git)
                                                  "--commit-id" (:id git)]))
            :environment-variables (->env ctx pk?))]))

(defn instance-config
  "Creates container instance configuration using the context and the
   skeleton config."
  [conf ctx]
  (let [tags (oci/sid->tags (get-in ctx [:build :sid]))]
    (-> conf
        (update :image-tag #(or % (config/version)))
        (oci/instance-config)
        (assoc :display-name (get-in ctx [:build :build-id])
               :freeform-tags tags)
        (update :containers patch-container ctx (get-in conf [:credentials :private-key])))))

(defn oci-runner [client conf ctx]
  (let [ch (ca/chan)
        r (oci/run-instance client (instance-config conf ctx))]
    (md/on-realized r
                    (fn [v]
                      (ca/put! ch v))
                    (fn [err]
                      (log/error "Got error from container instance:" err)
                      (ca/put! ch 1)))
    (ca/go
      (-> (ca/<! ch)
          (e/then-fire ctx #(e/build-completed-evt (:build ctx) %))))))

(defmethod r/make-runner :oci [ctx]
  (let [conf (oci/ctx->oci-config ctx :runner)
        client (-> conf
                   (oci/->oci-config)
                   (ci/make-context))]
    (partial oci-runner client conf)))
