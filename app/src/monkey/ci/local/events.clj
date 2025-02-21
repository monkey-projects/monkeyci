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
            [monkey.ci.script.config :as sc]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.local.config :as conf]))

;;; Context management

(def ctx->build (comp :build :event))

(def get-result em/get-result)
(def set-result em/set-result)

(def get-checkout-dir (comp b/checkout-dir ctx->build))

(def get-git-repo ::git-repo)

(defn set-git-repo [ctx r]
  (assoc ctx ::git-repo r))

(def get-workspace ::workspace)

(defn set-workspace [ctx ws]
  (assoc ctx ::workspace ws))

(def get-process ::process)

(defn set-process [ctx ws]
  (assoc ctx ::process ws))

(def get-ending ::ending)

(defn set-ending [ctx e]
  (assoc ctx ::ending e))

(defn set-ending! [d r]
  (log/debug "Child process finished, setting result:" r)
  (md/success! d r))

(def get-mailman ::mailman)

(defn set-mailman [ctx e]
  (assoc ctx ::mailman e))

(def get-api ::api)

(defn set-api [ctx e]
  (assoc ctx ::api e))

(def get-log-dir ::log-dir)

(defn set-log-dir [ctx e]
  (assoc ctx ::log-dir e))

(def get-lib-coords ::lib-coords)

(defn set-lib-coords [ctx e]
  (assoc ctx ::lib-coords e))

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
            (let [cmd (get-result ctx)]
              (log/debug "Starting child process:" cmd)
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

(defn add-ending [e]
  "Adds ending to the context"
  {:name ::add-ending
   :enter #(set-ending % e)})

(defn add-mailman [mm]
  "Adds mailman component to the context"
  {:name ::add-mailman
   :enter #(set-mailman % mm)})

(defn add-api [api]
  "Adds api configuration to the context"
  {:name ::add-api-conf
   :enter #(set-api % api)})

(defn add-log-dir [dir]
  "Adds log dir to the context"
  {:name ::add-log-dir
   :enter (fn [ctx]
            (when-not (fs/exists? dir)
              (fs/create-dirs dir))
            (set-log-dir ctx dir))})

(defn add-lib-coords [lib-coords]
  "Adds library coordinates configuration to the context"
  {:name ::add-lib-coords
   :enter #(set-lib-coords % lib-coords)})

(def handle-error
  {:name ::error
   :error (fn [ctx err]
            (log/error "Got error:" err)
            ctx)})

(def realize-ending
  {:name ::realize-ending
   :leave (fn [ctx]
            (set-ending! (get-ending ctx) (get-result ctx))
            ctx)})

;;; Handlers

(defn make-build-init-evt
  "Returns `build/initializing` event."
  [ctx]
  (-> (get-in ctx [:event :build])
      (assoc :status :initializing)
      (b/build-init-evt)))

(defn- child-config [ctx]
  (let [build (ctx->build ctx)
        {:keys [port token]} (get-api ctx)]
    (-> sc/empty-config
        (sc/set-build build)
        (sc/set-api {:url (str "http://localhost:" port)
                     :token token}))))

(defn generate-deps [script-dir lib-coords log-config]
  {:paths [script-dir]
   :aliases
   {:monkeyci/build
    (cond-> {:exec-fn 'monkey.ci.process/run
             :extra-deps {'com.monkeyci/app lib-coords}}
      log-config (assoc :jvm-opts
                        [(str "-Dlogback.configurationFile=" log-config)]))
    ;; m2 cache dir?
    }})

(defn prepare-child-cmd
  "Initializes child process command line"
  [ctx]
  (let [build (ctx->build ctx)
        log-file (comp fs/file (partial fs/path (get-log-dir ctx)))]
    {:dir (b/calc-script-dir (b/checkout-dir build) (b/script-dir build))
     :cmd ["clojure"
           "-Sdeps" (pr-str (generate-deps (b/script-dir build)
                                           (get-lib-coords ctx)
                                           nil))
           "-X:monkeyci/build"
           (pr-str {:config (child-config ctx)})]
     :out (log-file "out.log")
     :err (log-file "err.log")
     ;; On exit, post build/end event
     :exit-fn (fn [{:keys [exit]}]
                (log/info "Child process exited with exit code" exit)
                (try
                  (em/post-events (get-mailman ctx)
                                  [(b/build-end-evt build exit)])
                  (catch Exception ex
                    (log/error "Unable to post build/end event" ex))))}))

(defn build-init [ctx]
  (b/build-start-evt (ctx->build ctx)))

(defn build-end [ctx]
  ;; Just return the build, it will be set in the result
  (ctx->build ctx))

;;; Routes

(defn make-routes [conf mailman]
  [[:build/pending
    ;; Responsible for preparing the build environment and starting the
    ;; child process or container.
    [{:handler prepare-child-cmd
      :interceptors (cond-> [emi/handle-build-error
                             emi/no-result
                             start-process
                             (add-mailman mailman)
                             (add-api (conf/get-api conf))
                             (add-log-dir (conf/get-log-dir conf))
                             (add-lib-coords (conf/get-lib-coords conf))]
                      (get-in conf [:build :git]) (conj checkout-src)
                      true (conj (save-workspace (conf/get-workspace conf))))}
     {:handler make-build-init-evt}]]
   
   [:build/initializing
    ;; Build process is starting
    [{:handler build-init}]]
   
   [:build/end
    ;; Build has completed, clean up
    [{:handler build-end
      :interceptors [emi/no-result
                     (add-ending (conf/get-ending conf))
                     realize-ending]}]]])
