(ns monkey.ci.local.events
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
  
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [build :as b]
             [git :as git]]
            [monkey.ci.local.config :as conf]))

;;; Context management

(def ctx->build (comp :build :event))

(def get-checkout-dir (comp b/checkout-dir ctx->build))

(def get-git-repo ::git-repo)

(defn set-git-repo [ctx r]
  (assoc ctx ::git-repo r))

(def get-workspace ::workspace)

(defn set-workspace [ctx ws]
  (assoc ctx ::workspace ws))

(def get-cmd (comp :cmd :result))

(def get-process ::process)

(defn set-process [ctx ws]
  (assoc ctx ::process ws))

(defn set-process-result! [ctx r]
  (update ctx ::process-result md/success! r))

;;; Interceptors

(def checkout-src
  {:name ::checkout
   :enter (fn [ctx]
            (let [git (:git (ctx->build ctx))]
              (log/debug "Cloning repo" (:url git) "into" (:dir git))
              (set-git-repo ctx (git/clone+checkout git))))})

(defn save-workspace [dest]
  {:name ::save-ws
   :enter (fn [ctx]
            (log/debug "Copying repo files from " (get-checkout-dir ctx) "to" dest)
            (->> (git/copy-with-ignore (get-checkout-dir ctx) dest)
                 (set-workspace ctx)))})

(def start-process
  "Starts a child process using the command line stored in the result"
  {:name ::start-process
   :leave (fn [ctx]
            (let [cmd (get-cmd ctx)]
              (cond-> ctx
                cmd (set-process (bp/process cmd)))))})

(def start-container
  ;; TODO
  )

(defn restore-build-cache
  "Restores build cache to the checkout dir.  This is only done when running 
   in a container."
  [blob]
  {:name ::restore-build-cache
   :enter (fn [ctx]
            ;; TODO
            )})

(defn save-build-cache
  "Restores build cache from the checkout dir"
  [blob]
  {:name ::save-build-cache
   :enter (fn [ctx]
            ;; TODO
            )})

(def no-result
  "Empties result"
  {:name ::no-result
   :leave #(dissoc % :result)})

;;; Handlers

(defn make-build-init-evt
  "Returns `build/initializing` event."
  [ctx]
  (-> (get-in ctx [:event :build])
      (assoc :status :initializing)
      (b/build-init-evt)))

(defn prepare-child-cmd
  "Initializes child process command line"
  [ctx]
  (let [build (ctx->build ctx)]
    {:dir (b/calc-script-dir (b/checkout-dir build) (b/script-dir build))
     ;; TODO Config
     :cmd ["clojure" "-X:monkeyci/build"]
     ;; On exit, set the arg in the result deferred
     :exit-fn (partial set-process-result! ctx)}))

(defn build-start [ctx])

(defn build-end [ctx]
  ;; Clean up
  )

;;; Routes

(defn make-routes [conf]
  [[:build/pending
    ;; Responsible for preparing the build environment and starting the
    ;; child process or container.
    [{:handler prepare-child-cmd
      :interceptors (cond-> [no-result
                             start-process]
                      (get-in conf [:build :git]) (conj checkout-src)
                      true (conj (save-workspace (conf/get-workspace conf))))}
     {:handler make-build-init-evt}]]
   
   [:build/start
    ;; Build process has started, script can be loaded
    [{:handler build-start}]]
   
   [:build/end
    ;; Build has completed, clean up
    [{:handler build-end}]]])
