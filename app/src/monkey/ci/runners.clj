(ns monkey.ci.runners
  "Defines runner functionality.  These depend on the application configuration.
   A runner is able to execute a build script."
  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as b]
             [build :as build]
             [config :as config]
             [process :as p]
             [runtime :as rt]
             [utils :as u]]))

(defn- script-not-found [rt]
  (let [msg (str "No build script found at " (build/rt->script-dir rt))]
    (log/warn msg)
    {:exit 1
     :result :error
     :message msg}))

(defn- post-build-end [rt {:keys [exit message] :as res}]
  (rt/post-events rt (build/build-end-evt
                      (cond-> (rt/build rt)
                        message (assoc :message message))
                      exit))
  res)

(defn- log-build-result
  "Do some logging depending on the result"
  [{:keys [result exit] :as r}]
  (condp = (or result (build/exit-code->status exit) :unknown)
    :success (log/info "Success!")
    :warning (log/warn "Exited with warnings:" (:message r))
    :error   (log/error "Failure:" (:message r))
    :unknown (log/warn "Unknown result."))
  r)

(defn- cleanup-dir! [dir]
  (when (and dir (not= dir (u/cwd)))
    (log/debug "Cleaning up dir" dir)
    (u/delete-dir dir)))

(defn- cleanup-checkout-dirs!
  "Deletes the checkout dir, but only if it is not the current working directory."
  [{:keys [build] :as rt}]
  (when (:cleanup? build)
    (doseq [k [[:checkout-dir] [:git :ssh-keys-dir]]]
      (cleanup-dir! (get-in build k)))))

(defn build-local
  "Locates the build script locally and starts a child process that actually
   runs the build.  Returns a deferred that resolves when the child process has
   exited."
  [rt]
  (let [script-dir (build/rt->script-dir rt)]
    (rt/post-events rt {:type :build/start
                        :sid (build/get-sid rt)
                        :build (-> (rt/build rt)
                                   (build/build->evt)
                                   (assoc :start-time (u/now)))})
    (-> (md/chain
         (if (some-> (io/file script-dir) (.exists))
           (p/execute! rt)
           (md/success-deferred (script-not-found rt)))
         (partial post-build-end rt)
         log-build-result
         :exit)
        (md/finally
          #(cleanup-checkout-dirs! rt)))))

(defn download-git
  "Downloads from git into a temp dir, and designates that as the working dir."
  [rt]
  ;;(log/debug "Downloading from git using build config:" (:build rt))
  (let [git (get-in rt [:git :clone])
        conf (-> rt
                 rt/build
                 :git
                 (update :dir #(or % (build/calc-checkout-dir rt))))
        cd (git conf)]
    (log/debug "Checking out git repo" (:url conf) "into" (:dir conf))
    (-> rt
        (rt/update-build build/set-checkout-dir cd)
        (rt/update-build build/set-script-dir (build/calc-script-dir cd (build/rt->script-dir rt))))))

(defn download-src
  "Downloads the code from the remote source, if there is one.  If the source
   is already local, does nothing.  Returns an updated context."
  [rt]
  (cond-> rt
    (not-empty (-> rt rt/build :git)) (download-git)))

(defn create-workspace [rt]
  (let [ws (:workspace rt)
        ;; TODO For local builds, upload all workdir files according to .gitignore
        {:keys [checkout-dir sid]} (rt/build rt)
        dest (str (cs/join "/" sid) b/extension)]
    (when checkout-dir
      (log/info "Creating workspace using files from" checkout-dir)
      @(md/chain
        (b/save ws checkout-dir dest) ; TODO Check for errors
        (constantly (assoc-in rt [:build :workspace] dest))))))

(defn store-src
  "If a workspace configuration is present, uses it to store the source in
   the workspace.  This can then be used by other processes to download the
   cached files as needed."
  [rt]
  (cond-> rt
    (some? (:workspace rt)) (create-workspace)))

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

;;; Configuration handling

(defmulti normalize-runner-config (comp :type :runner))

(defmethod normalize-runner-config :default [conf]
  conf)

(defmethod config/normalize-key :runner [k conf]
  (config/normalize-typed k conf normalize-runner-config))

(defmethod rt/setup-runtime :runner [conf _]
  (make-runner conf))
