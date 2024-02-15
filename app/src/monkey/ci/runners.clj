(ns monkey.ci.runners
  "Defines runner functionality.  These depend on the application configuration.
   A runner is able to execute a build script."
  (:require [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as b]
             [build :as build]
             [config :as config]
             [context :as c]
             [events :as e]
             [process :as p]
             [runtime :as rt]
             [utils :as u]]))

(defn- script-not-found [rt]
  (log/warn "No build script found at" (build/script-dir rt))
  ;; Post build completed event so the failure is registered
  #_(c/post-events ctx (e/build-completed-evt b 1 :reason :script-not-found))
  ;; Nonzero exit code
  #_(assoc ctx :event {:result :error
                     :exit 1})
  {:exit 1
   :result :error
   :build (:build rt)})

(defn- post-build-completed [rt res]
  (rt/post-events rt (assoc res :type :build/completed))
  res)

(defn- log-build-result
  "Do some logging depending on the result"
  [{:keys [result] :as r}]
  (condp = (or result :unknown)
    :success (log/info "Success!")
    :warning (log/warn "Exited with warnings:" (:message r))
    :error   (log/error "Failure.")
    :unknown (log/warn "Unknown result."))
  r)

(defn- cleanup-dir! [dir]
  (when (and dir (not= dir (u/cwd)))
    (log/debug "Cleaning up dir" dir)
    (u/delete-dir dir)))

(defn- cleanup-checkout-dirs!
  "Deletes the checkout dir, but only if it is not the current working directory."
  [build]
  (doseq [k [:dir :ssh-keys-dir]]
    (cleanup-dir! (get-in build [:git k]))))

(defn build-local
  "Locates the build script locally and starts a child process that actually
   runs the build.  Returns a deferred that resolves when the child process has
   exited."
  [rt]
  (let [script-dir (build/script-dir rt)]
    (md/finally
      (md/chain
       (if (some-> (io/file script-dir) (.exists))
         (p/execute! rt)
         (md/success-deferred (script-not-found rt)))
       (partial post-build-completed rt)
       log-build-result
       :exit)
     #(cleanup-checkout-dirs! (:build rt)))))

(defn download-git
  "Downloads from git into a temp dir, and designates that as the working dir."
  [rt]
  (let [git (get-in rt [:git :clone])
        conf (-> (get-in rt [:build :git])
                 (update :dir #(or % (build/checkout-dir rt))))
        add-script-dir (fn [{{:keys [script-dir checkout-dir]} :build :as rt}]
                         (assoc-in rt [:build :script-dir] (build/calc-script-dir checkout-dir script-dir)))]
    (log/debug "Checking out git repo with config" conf)
    (-> rt
        (assoc-in [:build :checkout-dir] (git conf))
        (add-script-dir))))

(defn download-src
  "Downloads the code from the remote source, if there is one.  If the source
   is already local, does nothing.  Returns an updated context."
  [rt]
  (cond-> rt
    (not-empty (get-in rt [:build :git])) (download-git)))

(defn create-workspace [rt]
  (let [{:keys [store]} (:workspace rt)
        ;; TODO For local builds, upload all workdir files according to .gitignore
        {:keys [checkout-dir sid]} (:build rt)
        dest (str (cs/join "/" sid) b/extension)]
    (when checkout-dir
      (log/info "Creating workspace using files from" checkout-dir)
      @(md/chain
        (b/save store checkout-dir dest) ; TODO Check for errors
        (constantly (assoc-in rt [:build :workspace] dest))))))

(defn store-src
  "If a workspace configuration is present, uses it to store the source in
   the workspace.  This can then be used by other processes to download the
   cached files as needed."
  [rt]
  (cond-> rt
    (some? (get-in rt [:workspace :store])) (create-workspace)))

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

(defn ^:deprecated build
  "Event handler that reacts to a prepared build event.  The incoming event
   should contain the necessary information to start the build.  The build 
   runner performs the repo clone and checkout and runs the script.  Closes
   the channel when the build has completed."
  [{:keys [runner] {:keys [build]} :event :as ctx}]
  (try 
    (let [{:keys [build-id]} build
          ;; Use combination of checkout dir and build id for checkout
          workdir (c/checkout-dir ctx build-id)
          conf (assoc-in build [:git :dir] workdir)]
      (log/debug "Starting build with config:" conf)
      (-> ctx
          (assoc :build conf)
          (runner)
          (take-and-close)))
    (catch Exception ex
      (log/error "Failed to build" (:sid build) ex)
      (c/post-events ctx
                     {:type :build/completed
                      :build build
                      :result :error
                      :exit 2
                      :exception ex}))))

;;; Configuration handling

(defmulti normalize-runner-config (comp :type :runner))

(defmethod normalize-runner-config :default [conf]
  conf)

(defmethod config/normalize-key :runner [k conf]
  (config/normalize-typed k conf normalize-runner-config))

(defmethod rt/setup-runtime :runner [conf _]
  (make-runner conf))
