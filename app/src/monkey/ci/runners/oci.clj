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
            [monkey.ci.events.core :as ec]
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
                                "--sid" (format-sid (b/get-sid rt))]
                         (not-empty git) (concat ["-u" (:url git)
                                                  "-b" (:branch git)
                                                  "--commit-id" (:id git)]))
            :environment-variables (->env rt))]))

(defn instance-config
  "Creates container instance configuration using the context and the
   skeleton config."
  [conf rt]
  (let [tags (oci/sid->tags (b/get-sid rt))]
    (-> conf
        (update :image-tag #(or % (config/version)))
        (oci/instance-config)
        (assoc :display-name (b/get-build-id rt))
        (update :freeform-tags merge tags)
        (update :containers patch-container rt))))

(defn wait-for-script-end-event
  "Returns a deferred that realizes when the script/end event has been received."
  [events sid]
  (ec/wait-for-event events
                     {:types #{:script/end}
                      :sid sid}))

(def max-script-timeout
  "Max msecs a build script can run before we terminate it"
  ;; One hour
  (* 3600 60 1000))

(defn oci-runner
  "Runs the build script as an OCI container instance.  Returns a deferred with
   the container exit code."
  [client conf rt]
  (-> (oci/run-instance client (instance-config conf rt)
                        {:delete? true
                         :exited? (fn [id]
                                    (md/chain
                                     (md/timeout!
                                      (wait-for-script-end-event (:events rt) (b/get-sid rt))
                                      max-script-timeout ::timeout)
                                     (fn [_]
                                       (oci/get-full-instance-details client id))))})
      (md/chain
       (fn [r]
         (or (-> r :body :containers first :exit-code) 1))
       (fn [r]
         (rt/post-events rt (b/build-end-evt (rt/build rt) r))
         r))
      (md/catch
          (fn [ex]
            (log/error "Got error from container instance:" ex)
            (rt/post-events rt (b/build-end-evt (rt/build rt) 1))))))

(defmethod r/make-runner :oci [rt]
  (let [conf (:runner rt)
        client (-> conf
                   (oci/->oci-config)
                   (ci/make-context))]
    (partial oci-runner client conf)))

(defmethod r/normalize-runner-config :oci [conf]
  (oci/normalize-config conf :runner))
