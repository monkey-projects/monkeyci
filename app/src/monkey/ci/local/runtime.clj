(ns monkey.ci.local.runtime
  "Set up runtime for local builds"
  (:require [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as a]
             [blob :as blob]
             [cache :as c]
             [protocols :as p]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.local.config :as lc]
            [monkey.ci.runtime.common :as rc]))

(defn- new-mailman []
  (em/make-component {:type :manifold}))

(defn- blob-store [dir]
  (blob/->DiskBlobStore (str dir)))

(defn- new-artifacts [conf]
  (a/make-blob-repository (blob-store (lc/get-artifact-dir conf))
                          (lc/get-build conf)))

(defn- new-cache [conf]
  (c/make-blob-repository (blob-store (lc/get-cache-dir conf))
                          (lc/get-build conf)))

(defrecord FixedBuildParams [params]
  p/BuildParams
  (get-build-params [_]
    (md/success-deferred params)))

(defn- new-params [conf]
  (->FixedBuildParams (lc/get-params conf)))

(defn make-system
  "Creates a component system that can be used for local builds"
  [conf]
  (co/system-map
   :mailman (new-mailman)
   :artifacts (new-artifacts conf)
   :cache (new-cache conf)
   :params (new-params conf)))

(defn start-and-post
  "Starts component system and posts an event to the event broker to trigger
   the action flow.
   Returns a deferred that will realize when the build ends, which can be used
   to wait upon."
  [conf evt]
  (let [result (md/deferred)]
    (rc/with-system-async (make-system (assoc conf :result result))
      (fn [sys]
        (em/post-events (:mailman sys) [evt])
        result))))
