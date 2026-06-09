(ns monkey.ci.cli.events
  "Mailman event handlers for CLI builds.  Note that these events are dispatched
   and handled purely internal."
  (:require [babashka
             [fs :as fs]
             [process :as bp]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.cli
             [build :as b]
             [config :as conf]
             [process :as p]]
            [monkey.ci.events.builders :as eb]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.script.config :as sc]
            [monkey.ci.utils.path :as up]
            [monkey.mailman.core :as mmc]))

;;; Context management

(def ctx->build (comp :build :event))

(def get-result emi/get-result)

(def set-result emi/set-result)

(def get-checkout-dir (comp b/checkout-dir ctx->build))

(def get-git-repo ::git-repo)

(defn set-git-repo [ctx r]
  (assoc ctx ::git-repo r))

(def get-workspace (comp :workspace emi/get-state))

(defn set-workspace [ctx ws]
  (emi/update-state ctx assoc :workspace ws))

(def get-mailman emi/get-mailman)

(def get-api ::api)

(defn set-api [ctx e]
  (assoc ctx ::api e))

(def get-log-dir ::log-dir)

(defn set-log-dir [ctx e]
  (assoc ctx ::log-dir e))

(def get-child-opts ::child-opts)

(defn set-child-opts [ctx e]
  (assoc ctx ::child-opts e))

(def get-build-opts ::build-opts)

(defn set-build-opts [ctx bo]
  (assoc ctx ::build-opts bo))

(def get-process ::process)

(defn set-process [ctx ws]
  (assoc ctx ::process ws))

;;; Interceptors

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

(defn add-child-opts [child-opts]
  "Adds options for child process to the context"
  {:name ::add-child-opts
   :enter #(set-child-opts % child-opts)})

(defn add-build-opts
  "Adds additional build options to the context, which should be passed to the script."
  [config]
  {:name ::add-build-opts
   :enter (fn [ctx]
            (set-build-opts ctx (select-keys config [:filter])))})

(defn save-workspace
  "Interceptor that copies the build checkout directory into a temporary workspace
   directory before the child process starts.  The destination path is taken from
   the build configuration.  The workspace path is stored in context state so that
   subsequent interceptors (e.g. container jobs) can locate it.

   On `:enter`: copies `checkout-dir` → `dest`, records `:workspace` in state.
   The copy respects the directory structure but does NOT apply .gitignore filtering
   (that would require shelling out to git; a future iteration can add that).

   `dest` should be an absolute path, typically `(conf/get-workspace run-conf)`."
  [dest]
  {:name ::save-workspace
   :enter (fn [ctx]
            (let [src (get-checkout-dir ctx)
                  dst (io/file (str dest))]
              (log/debug "Saving workspace from" src "to" dst)
              (if src
                (do
                  (fs/create-dirs dst)
                  (fs/copy-tree src dst {:replace-existing true})
                  (set-workspace ctx (str dst)))
                (do
                  (log/warn "No checkout dir in build, skipping workspace save")
                  ctx))))})

(defn delete-work-dir
  "Interceptor that deletes the working directory on `:leave`, unless the
   `clean` flag is `false` in the run configuration.

   Wire this into the `:build/end` route so cleanup happens after the build
   terminates.

   `run-conf` — the CLI run configuration map."
  [run-conf]
  {:name ::delete-work-dir
   :leave (fn [ctx]
            (let [ws (conf/get-work-dir run-conf)]
              (cond
                (not= :success (get-in ctx [:result :status]))
                (log/info "Build failed, keeping working directory for inspection.")
                (not (conf/get-clean run-conf))
                (log/info "Skipping working directory cleanup (--no-clean)")
                ws
                (do
                  (log/debug "Deleting working directory at" ws)
                  (when-not (fs/delete-tree ws {:force true})
                    (log/warn "Failed to delete work dir" ws)))
                :else
                (log/debug "No working directory configured, nothing to delete")))
            ctx)})

(defn update-job-in-state [ctx job-id f & args]
  (apply emi/update-state ctx update-in [:jobs job-id] f args))

(def add-job-to-state
  {:name ::add-job-to-state
   :enter (fn [{:keys [event] :as ctx}]
            (update-job-in-state ctx (:job-id event) merge (select-keys event [:status :result])))})

(def start-process
  "Starts a child process using the command line stored in the result"
  {:name ::start-process
   :leave (fn [ctx]
            (let [cmd (get-result ctx)]
              (log/debug "Starting child process:" cmd)
              (cond-> ctx
                cmd (set-process (bp/process cmd)))))})

(defn deliver-end [p]
  {:name ::deliver-end
   :leave (fn [ctx]
            (deliver p (get-result ctx))
            ctx)})

;;; Handlers

(defn make-build-init-evt
  "Returns `build/initializing` event."
  [ctx]
  (-> (get-in ctx [:event :build])
      (assoc :status :initializing)
      (eb/build-init-evt)))

(defn- absolutize-script-dir [build]
  (b/set-script-dir build (up/abs-path (b/checkout-dir build) (or (b/script-dir build)
                                                                  b/default-script-dir))))

(defn- child-config [ctx]
  (let [build (ctx->build ctx)]
    (-> sc/empty-config
        (sc/set-build (absolutize-script-dir build))
        (sc/set-api (select-keys (get-api ctx) [:url :token]))
        (sc/set-job-filter (:filter (get-build-opts ctx))))))

(defn get-runner
  "Determines whether to run babashka or clojure for the scripts.  Either by reading
   the child options, or checking if a `deps.edn` file exists.  By default, returns `:bb`"
  [ctx sd]
  (or (get-in ctx [::child-opts :runner])
      (if (fs/exists? (fs/path sd "deps.edn")) :clj :bb)))

(defn generate-deps [{:keys [lib-coords log-config]}]
  (cond-> (p/generate-deps nil)
    lib-coords (update-in [:aliases :monkeyci/build :extra-deps] mc/assoc-some 'com.monkeyci/script lib-coords)
    log-config (p/update-alias assoc :jvm-opts
                               [(str "-Dlogback.configurationFile=" log-config)])))

(defn- ctx->script-dir [ctx]
  (let [build (ctx->build ctx)]
    (b/calc-script-dir (b/checkout-dir build) (b/script-dir build))))

(defn- read-existing-bb-edn [ctx]
  (let [p (fs/path (ctx->script-dir ctx) "bb.edn")]
    (when (fs/exists? p)
      (edn/read-string (slurp (fs/file p))))))

(defn generate-bb-conf [ctx]
  (let [{:keys [lib-coords]} (get-child-opts ctx)
        api (get-api ctx)
        bb (read-existing-bb-edn ctx)]
    (mc/deep-merge
     bb
     {:deps {'com.monkeyci/script lib-coords
             'meta-merge/meta-merge {:mvn/version "1.0.0"}}
      :paths ["."]
      :tasks
      {'script
       {:task '(exec 'monkey.ci.script.core/run-bb-cli)
        :exec-args (-> (select-keys api [:url :token])
                       (assoc :sid (->> (b/sid (ctx->build ctx))
                                        (cs/join "/"))))}}})))

(defn- bb-cmd [ctx]
  (let [p (fs/create-temp-file {:prefix "bb-" :suffix ".edn"})
        bb-path (fs/which "bb")]
    (when-not bb-path
      (throw (ex-info "Babashka installation has not been found.  Please install babashka first." {})))
    (spit (fs/file p) (generate-bb-conf ctx))
    [(str bb-path)
     "--config" (str p)
     "run" "script"]))

(defn- clj-cmd [ctx]
  ["clojure"
   "-Sdeps" (pr-str (generate-deps (get-child-opts ctx)))
   "-X:monkeyci/build"
   (pr-str {:config (child-config ctx)})])

(defn- gen-cmd [ctx]
  (let [sd (ctx->script-dir ctx)]
    (when-not (fs/exists? sd)
      (throw (ex-info "Script dir does not exist" {:dir sd})))
    (case (get-runner ctx sd)
      :clj (clj-cmd ctx)
      ;; default
      (bb-cmd ctx))))

(defn prepare-child-cmd
  "Initializes child process command line"
  [ctx]
  (let [build (ctx->build ctx)
        log-file (comp fs/file (partial fs/path (get-log-dir ctx)))
        on-exit (fn [{:keys [exit]}]
                  ;; On exit, post build/end event
                  (log/info "Child process exited with exit code" exit)
                  (try
                    (mmc/post-events (get-mailman ctx)
                                     [(eb/build-end-evt build exit)])
                    (catch Exception ex
                      (log/error "Unable to post build/end event" ex))))]
    {:dir (b/calc-script-dir (b/checkout-dir build) (b/script-dir build))
     :cmd (gen-cmd ctx)
     :out (log-file "out.log")
     :err (log-file "err.log")
     :exit-fn (p/exit-fn on-exit)}))

(defn build-init [ctx]
  (eb/build-start-evt (ctx->build ctx)))

(defn build-end [ctx]
  ;; Return the build with job details from state, it will be set in the result
  (-> (ctx->build ctx)
      (merge (select-keys (emi/get-state ctx) [:jobs]))))

;;; Routes

(defn make-routes [conf mailman]
  (let [state (emi/with-state (or (:state conf) (atom {})))]
    (concat
     [[:build/pending
       ;; Responsible for preparing the build environment and starting the
       ;; child process or container.  `save-workspace` runs on enter (before
       ;; `start-process`) so the workspace is ready before the child starts.
       [{:handler prepare-child-cmd
         :interceptors [emi/handle-build-error
                        state
                        emi/no-result
                        start-process
                        (emi/add-mailman mailman)
                        (add-api (conf/get-api conf))
                        (add-log-dir (conf/get-log-dir conf))
                        (add-child-opts (conf/get-child-opts conf))
                        (add-build-opts conf)
                        (save-workspace (conf/get-workspace conf))]}
        {:handler make-build-init-evt}]]

      [:build/initializing
       ;; Build process is starting
       [{:handler build-init}]]

      [:build/end
       ;; Build has completed.  `delete-workspace` runs on leave so cleanup
       ;; happens after the result has been delivered.
       [{:handler build-end
         :interceptors [emi/no-result
                        state
                        (delete-work-dir conf)
                        (deliver-end (conf/get-ending conf))]}]]

      [:job/end
       ;; Updates state to add job result
       [{:handler (constantly nil)
         :interceptors [state
                        add-job-to-state]}]]])))
