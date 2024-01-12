(ns monkey.ci.runners
  "Defines runner functionality.  These depend on the application configuration.
   A runner is able to execute a build script."
  (:require [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as b]
             [context :as c]
             [events :as e]
             [process :as p]
             [utils :as u]]))

(def default-script-dir ".monkeyci")

(defn- calc-script-dir
  "Given an (absolute) working directory and scripting directory, determines
   the absolute script dir."
  [wd sd]
  (->> (or sd default-script-dir)
       (u/abs-path wd)
       (io/file)
       (.getCanonicalPath)))

(defn- script-not-found [{{:keys [script-dir]} :build :as ctx}]
  (log/warn "No build script found at" script-dir)
  ;; Nonzero exit code
  (assoc ctx :event {:result :error
                     :exit 1}))

(defn- log-build-result
  "Do some logging depending on the result"
  [{:keys [result] :as evt}]
  (condp = (or result :unknown)
    :success (log/info "Success!")
    :warning (log/warn "Exited with warnings:" (:message evt))
    :error   (log/error "Failure.")
    :unknown (log/warn "Unknown result.")))

(defn- cleanup-checkout-dir!
  "Deletes the checkout dir, but only if it is not the current working directory."
  [build]
  (let [wd (get-in build [:git :dir])]
    (when (and wd (not= wd (u/cwd)))
      (log/debug "Cleaning up checkout dir" wd)
      (u/delete-dir wd))))

(defn build-completed [{{:keys [exit] :as evt} :event :keys [build]}]
  (log-build-result evt)
  (cleanup-checkout-dir! build)
  exit)

(defn build-completed-tx [ctx]
  ;; Filter by build id to avoid deleting the wrong checkout dir
  (comp (filter (comp (partial = (get-in ctx [:build :build-id]))
                      :build-id
                      :build))
        (map (partial e/with-ctx ctx))))

(defn build-local
  "Locates the build script locally and dispatches another event with the
   script details in them.  If no build script is found, dispatches a build
   complete event."
  [{:keys [event-bus] :as ctx}]
  (let [{:keys [script-dir]} (:build ctx)]
    (->> (if (some-> (io/file script-dir) (.exists))
           (e/do-and-wait #(p/execute! ctx)
                          event-bus :build/completed (build-completed-tx ctx))
           (ca/to-chan! [(script-not-found ctx)]))
         (ca/take 1) ; Limit to one because `wait-for` will return a never-closing channel
         (vector)
         (ca/map build-completed))))

(defn download-git
  "Downloads from git into a temp dir, and designates that as the working dir."
  [ctx]
  (let [git (get-in ctx [:git :fn])
        conf (-> (get-in ctx [:build :git])
                 (update :dir #(or % (c/checkout-dir ctx))))
        add-script-dir (fn [{{:keys [script-dir checkout-dir]} :build :as ctx}]
                         (assoc-in ctx [:build :script-dir] (calc-script-dir checkout-dir script-dir)))]
    (log/debug "Checking out git repo with config" conf)
    (-> ctx
        (assoc-in [:build :checkout-dir] (git conf))
        (add-script-dir))))

(defn download-src
  "Downloads the code from the remote source, if there is one.  If the source
   is already local, does nothing.  Returns an updated context."
  [ctx]
  (cond-> ctx
    (not-empty (get-in ctx [:build :git])) (download-git)))

(defn create-workspace [ctx]
  (let [{:keys [store]} (:workspace ctx)
        ;; TODO For local builds, upload all workdir files according to .gitignore
        {:keys [checkout-dir build-id]} (:build ctx)
        dest (str build-id b/extension)]
    (when checkout-dir
      (log/info "Creating workspace using files from" checkout-dir)
      @(md/chain
        (b/save store checkout-dir dest) ; TODO Check for errors
        (constantly (assoc-in ctx [:build :workspace] dest))))))

(defn store-src
  "If a workspace configuration is present, uses it to store the source in
   the workspace.  This can then be used by other processes to download the
   cached files as needed."
  [ctx]
  (cond-> ctx
    (some? (get-in ctx [:workspace :store])) (create-workspace)))

;; Creates a runner fn according to its type
(defmulti make-runner (comp :type :runner))

(defmethod make-runner :child [_]
  (log/info "Using child process runner")
  (comp build-local store-src download-src))

(defmethod make-runner :noop [_]
  ;; For testing
  (log/debug "No-op runner configured")
  (constantly 1))

(defmethod make-runner :default [_]
  ;; Fallback
  (log/warn "No runner configured, using fallback configuration")
  (constantly 2))

(defn- take-and-close
  "Takes the first value from channel `ch` and closes it.  Closing the channel
   is necessary to ensure cleanup."
  [ch]
  (ca/go
    (let [r (ca/<! ch)]
      (ca/close! ch)
      r)))

(defn build
  "Event handler that reacts to a prepared build event.  The incoming event
   should contain the necessary information to start the build.  The build 
   runner performs the repo clone and checkout and runs the script.  Closes
   the channel when the build has completed."
  [{:keys [runner] :as ctx}]
  (try 
    (let [{:keys [build-id] :as build} (get-in ctx [:event :build])
          ;; Use combination of checkout dir and build id for checkout
          workdir (c/checkout-dir ctx build-id)
          conf (assoc-in build [:git :dir] workdir)]
      (log/debug "Starting build with config:" conf)
      (-> ctx
          (assoc :build conf)
          (runner)
          (take-and-close)))
    (catch Exception ex
      (log/error "Failed to build" ex)
      (e/post-event (:event-bus ctx)
                    {:type :build/completed
                     :result :error
                     :exit 2
                     :exception ex}))))
