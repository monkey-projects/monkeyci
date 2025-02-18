(ns monkey.ci.runners.local
  "Event routes for local build runners.  When running a build locally via cli,
   these routes will be registered in mailman and will perform all necessary
   build steps.

   Running a local build uses the same event flow as server-side builds.
   Depending on the configuration, routes may differ (e.g. different handlers
   or interceptors).  A local build can either run in a child process, or it
   can run in a container.  In any case, the container jobs are run in containers
   and action jobs are run by the script process.

   Blobs (artifacts, caches) are always stored locally, but this can vary
   depending on configuration.  If run in a container, a volume can be used.

   The build controller, responsible for managing build params and events, is
   run in the main process.  Builds connect to it using http, same as for 
   server-side builds."
  (:require [monkey.ci.build :as b]))

;;; Interceptors

(def checkout-src
  {:name ::checkout
   :enter (fn [ctx]
            ;; TODO Download source from git
            )})

(def save-workspace)

(def start-child)

(def start-container)

(def restore-build-cache)

(def save-build-cache)

;;; Handlers

(defn build-pending [ctx]
  (-> (get-in ctx [:event :build])
      (assoc :status :initializing)
      (b/build-init-evt)))

(defn build-start [ctx])

(defn build-init-child
  "Starts the build using a child process."
  [ctx])

(defn build-end [ctx])

;;; Routes

(defn make-routes [conf]
  [[:build/pending
    ;; Responsible for preparing the build environment and starting the
    ;; child process or container.
    [{:handler build-pending}]]
   [:build/initializing
    ;; Checkout build code, start controller
    [{:handler build-init-child}]]
   [:build/start
    ;; Build process has started, script can be loaded
    [{:handler build-start}]]
   [:build/end
    ;; Build has completed
    [{:handler build-end}]]])
