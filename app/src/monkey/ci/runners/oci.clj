(ns monkey.ci.runners.oci
  (:require [babashka.fs :as fs]
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
             [runtime :as rt]
             [utils :as u]]
            [monkey.oci.container-instance.core :as ci]))

(def build-container "build")
(def format-sid (partial cs/join "/"))

(defn- ->env [rt pk?]
  (->> (cond-> (ctx/ctx->env rt)
         ;; FIXME This will turn out wrong if the private key is specified elsewhere
         ;; TODO Move this to oci ns
         pk? (assoc-in [:oci :credentials :private-key] (str oci/key-dir "/" oci/privkey-file)))
       (config/config->env)
       (mc/map-keys name)
       (mc/remove-vals empty?)))

(defn- patch-container [[conf] rt pk?]
  (let [git (get-in rt [:build :git])]
    [(assoc conf
            :display-name build-container
            :arguments (cond-> ["-w" oci/checkout-dir "build" "run"
                                "--sid" (format-sid (get-in rt [:build :sid]))]
                         (not-empty git) (concat ["-u" (:url git)
                                                  "-b" (:branch git)
                                                  "--commit-id" (:id git)]))
            :environment-variables (->env rt pk?))]))

(defn instance-config
  "Creates container instance configuration using the context and the
   skeleton config."
  [conf rt]
  (let [tags (oci/sid->tags (get-in rt [:build :sid]))]
    (-> conf
        (update :image-tag #(or % (config/version)))
        (oci/instance-config)
        (assoc :display-name (get-in rt [:build :build-id])
               :freeform-tags tags)
        (update :containers patch-container rt (get-in conf [:credentials :private-key])))))

(defn oci-runner [client conf rt]
  (-> (oci/run-instance client (instance-config conf rt))
      (md/chain
       (fn [r]
         (rt/post-events rt (e/build-completed-evt (:build rt) r))
         r))
      (md/catch
          (fn [ex]
            (log/error "Got error from container instance:" ex)
            (rt/post-events rt (e/build-completed-evt (:build rt) 1))))))

(defmethod r/make-runner :oci [rt]
  (let [conf (:runner rt)
        client (-> conf
                   (oci/->oci-config)
                   (ci/make-context))]
    (partial oci-runner client conf)))

(defmethod r/normalize-runner-config :oci [conf]
  (oci/normalize-config conf :runner))
