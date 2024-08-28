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
             [script :as s]
             [spec :as spec]
             [utils :as u]
             [workspace :as ws]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.spec.build :as sb]))

(defn- script-not-found [build]
  (let [msg (str "No build script found at " (build/script-dir build))]
    (log/warn msg)
    {:exit 1
     :result :error
     :message msg}))

(defn- post-build-end [rt build {:keys [exit message] :as res}]
  (md/chain
   (rt/post-events rt (build/build-end-evt
                       (cond-> build
                         message (assoc :message message))
                       exit))
   (constantly res)))

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
  [build]
  (when (:cleanup? build)
    (doseq [k [[:checkout-dir] [:git :ssh-keys-dir]]]
      (cleanup-dir! (get-in build k)))))

(defn build-local
  "Locates the build script locally and starts a child process that actually
   runs the build.  Returns a deferred that resolves when the child process has
   exited."
  [build rt]
  (spec/valid? ::sb/build build)
  (let [script-dir (build/script-dir build)]
    (rt/post-events rt {:type :build/start
                        :sid (build/sid build)
                        :build (-> build
                                   (build/build->evt)
                                   (assoc :start-time (u/now)))})
    (-> (md/chain
         (if (some-> (io/file script-dir) (.exists))
           (p/execute! build rt)
           (md/success-deferred (script-not-found build)))
         (partial post-build-end rt build)
         log-build-result
         :exit)
        (md/finally
          #(cleanup-checkout-dirs! build)))))

(defn download-git
  "Downloads from git into a temp dir, and designates that as the working dir."
  [build rt]
  ;;(log/debug "Downloading from git using build config:" (:build rt))
  (let [git (get-in rt [:git :clone])
        conf (-> build
                 :git
                 (update :dir #(or % (build/calc-checkout-dir rt build))))
        cd (git conf)]
    (log/debug "Checking out git repo" (:url conf) "into" (:dir conf))
    (-> build
        (build/set-checkout-dir cd)
        (build/set-script-dir (build/calc-script-dir cd (build/script-dir build))))))

(defn download-src
  "Downloads the code from the remote source, if there is one.  If the source
   is already local, does nothing.  Returns an updated context."
  [build rt]
  (cond-> build
    (not-empty (:git build)) (download-git rt)))

(defn store-src
  "If a workspace configuration is present, uses it to store the source in
   the workspace.  This can then be used by other processes to download the
   cached files as needed."
  [build rt]
  (cond-> build
    (some? (:workspace rt)) (ws/create-workspace rt)))

;; Creates a runner fn according to its type
(defmulti make-runner (comp :type :runner))

(defmethod make-runner :child [_]
  (log/info "Using child process runner")
  (fn [build rt]
    (log/debug "Running build in child process:" build)
    (-> build
        (download-src rt)
        (store-src rt)
        (build-local rt))))

(defmethod make-runner :noop [_]
  ;; For testing
  (log/debug "No-op runner configured")
  (constantly 1))

(defmethod make-runner :in-process [_]
  ;; In-process runner that loads and executes the build script in this process instead
  ;; of starting a child process.
  (fn [build rt]
    (if (bc/failed? (s/exec-script! (assoc rt :build build)))
      1
      0)))

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
