(ns monkey.ci.runners.oci
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [config :as config]
             [oci :as oci]
             [runners :as r]
             [runtime :as rt]
             [utils :as u]]
            [monkey.oci.container-instance.core :as ci]))

(def build-container "build")
(def format-sid (partial cs/join "/"))

(defn- ->env [rt]
  (->> (rt/rt->env rt)
       (config/config->env)
       (mc/map-keys name)
       (mc/remove-vals empty?)))

(defn- patch-container [[conf] rt]
  (let [git (get-in rt [:build :git])]
    [(assoc conf
            :display-name build-container
            :arguments (cond-> ["-w" oci/checkout-dir "build" "run"
                                "--sid" (format-sid (get-in rt [:build :sid]))]
                         (not-empty git) (concat ["-u" (:url git)
                                                  "-b" (:branch git)
                                                  "--commit-id" (:id git)]))
            :environment-variables (->env rt))]))

(defn instance-config
  "Creates container instance configuration using the context and the
   skeleton config."
  [conf rt]
  (let [tags (oci/sid->tags (get-in rt [:build :sid]))]
    (-> conf
        (update :image-tag #(or % (config/version)))
        (oci/instance-config)
        (assoc :display-name (get-in rt [:build :build-id]))
        (update :freeform-tags merge tags)
        (update :containers patch-container rt))))

(defn oci-runner
  "Runs the build script as an OCI container instance.  Returns a deferred with
   the container exit code."
  [client conf rt]
  (-> (oci/run-instance client (instance-config conf rt) {:delete? true})
      (md/chain
       (fn [r]
         (or (-> r :body :containers first :exit-code) 1))
       (fn [r]
         (rt/post-events rt (b/build-end-evt (:build rt) r))
         r))
      (md/catch
          (fn [ex]
            (log/error "Got error from container instance:" ex)
            (rt/post-events rt (b/build-end-evt (:build rt) 1))))))

(defmethod r/make-runner :oci [rt]
  (let [conf (:runner rt)
        client (-> conf
                   (oci/->oci-config)
                   (ci/make-context))]
    (partial oci-runner client conf)))

(defmethod r/normalize-runner-config :oci [conf]
  (oci/normalize-config conf :runner))
