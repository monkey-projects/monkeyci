(ns monkey.ci.runners.controller
  "Functions for running the application as a controller."
  (:require [babashka.fs :as fs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [blob :as blob]
             [build :as b]
             [config :as c]
             [errors :as err]
             [protocols :as p]
             [utils :as u]
             [workspace :as ws]]
            [monkey.ci.events
             [builders :as eb]
             [mailman :as em]]
            [monkey.ci.events.mailman.interceptors :as emi]))

(def run-path (comp :run-path :config))
(def abort-path (comp :abort-path :config))
(def exit-path (comp :exit-path :config))
(def m2-cache-dir (comp :m2-cache-path :config))

(defn- post-events [rt evt]
  (em/post-events (:mailman rt) evt))

(defn- post-start-evt
  "Post `build/start` event.  Indicates the build has started, but the script is not running yet.
   When the script container is running, the `script/initializing` event is posted."
  [{:keys [build] :as rt}]
  (post-events rt [(b/build-start-evt build)])
  rt)

(defn- create-run-file [rt]
  (log/debug "Creating run file at:" (run-path rt))
  (some-> (run-path rt)
          (fs/create-file))
  rt)

(defn- create-abort-file [rt]
  (log/debug "Creating abort file at:" (abort-path rt))
  (some-> (abort-path rt)
          (fs/create-file))
  rt)

(defn- download-git
  "Downloads from git into a temp dir, and designates that as the working dir."
  [build rt]
  ;;(log/debug "Downloading from git using build config:" (:build rt))
  (let [git (get-in rt [:git :clone])
        conf (-> build
                 :git
                 (update :dir #(or % (b/calc-checkout-dir rt build))))
        cd (git conf)]
    (log/debug "Checking out git repo" (:url conf) "into" (:dir conf))
    (-> build
        (b/set-checkout-dir cd)
        (b/set-script-dir (b/calc-script-dir cd (b/script-dir build))))))

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

(defn- download-and-store-src [rt]
  (assoc rt :build (-> (:build rt)
                       (download-src rt)
                       (store-src rt))))

(defn- repo-cache-location
  "Returns the location to use in the repo cache for the given build."
  [build]
  (str (cs/join "/" (take 2 (b/sid build))) blob/extension))

(defn- restore-build-cache [{:keys [build build-cache] :as rt}]
  (log/debug "Restoring build cache for build" (b/sid build))
  ;; Restore to parent because the dir is in the archive
  @(blob/restore build-cache (repo-cache-location build) (str (fs/parent (m2-cache-dir rt))))
  rt)

(defmacro app-hash
  "Calculates the md5 hash for deps.edn at compile time."
  []
  (u/file-hash "deps.edn"))

(defn- save-build-cache [{:keys [build build-cache] :as rt}]
  (let [loc (repo-cache-location build)
        md (some-> (p/get-blob-info build-cache loc)
                   (deref)
                   :metadata)]
    ;; Only perform save if the hash has changed
    (if (not= (app-hash) (:app-hash md))
      (try
        (log/debug "Saving build cache for build" (b/sid build))
        @(blob/save build-cache
                    (m2-cache-dir rt)
                    loc
                    ;; TODO Include hash of the build deps.edn as well
                    {:app-hash (app-hash)})
        (catch Throwable ex
          (log/error "Failed to save build cache" ex)))
      (log/debug "Not saving build cache, hash is unchanged"))
    rt))

(defn- wait-until-run-file-deleted [rt]
  (md/future
    (let [rp (run-path rt)]
      (log/debug "Waiting until run file has been deleted at:" rp)
      (while (fs/exists? rp)
        (Thread/sleep 500))
      (log/debug "Run file deleted, build script finished."))))

(defn- make-result [exit-code & [msg]]
  (-> {:exit-code exit-code}
      (mc/assoc-some :message msg)))

(defn- set-result [rt res]
  (assoc rt :result res))

(def get-result :result)

(defn- read-exit-code [rt]
  (let [p (exit-path rt)]
    (when (fs/exists? p)
      (Integer/parseInt (.strip (slurp p))))))

(defn- wait-for-exit
  "Waits until either the run file has been deleted, or the `script-exit` deferred is
   realized, whichever is first.  Returns the runtime with a result added."
  [rt]
  (set-result
   rt
   ;; It can happen sporadically that the run file never gets deleted, even though the
   ;; script process has actually exited.  Cause unknown, and very hard to determine
   ;; because it's running in a cloud container.  So, in addition, we also capture
   ;; the `script/end` event, which is also an indication that the script has ended.
   ;; We still keep monitoring the run file, because in case of error, the `script/end`
   ;; event may never be posted.
   (-> (md/alt
        (md/chain
         (wait-until-run-file-deleted rt)
         (fn [_]
           (-> (or (read-exit-code rt) err/error-process-failure)
               (make-result))))
        (md/chain
         (or (:script-exit rt) (md/deferred))
         (fn [{:keys [status message]}]
           (make-result (if (= :success status) 0 err/error-script-failure)
                        message))))
       (md/timeout! c/max-script-timeout)
       (deref))))

(defn- post-end-evt [{:keys [build] :as rt}]
  (let [{:keys [exit-code message]} (get-result rt)]
    (log/debug "Posting :build/end event for exit code" exit-code)
    (post-events rt [(b/build-end-evt (mc/assoc-some build :message message) exit-code)])
    rt))

(defn- post-failure-evt [{:keys [build] :as rt} msg]
  (try
    (post-events rt [(-> build
                         (mc/assoc-some :message msg)
                         (b/build-end-evt build err/error-process-failure))])
    (catch Exception ex
      (log/warn "Failed to post :build/end event" ex))))

(defn run-controller [rt]
  (try
    (-> rt
        (post-start-evt)
        (download-and-store-src)
        (restore-build-cache)
        (create-run-file)
        (wait-for-exit)
        (save-build-cache)
        ;; Post the end event last, because it's the trigger for deleting the container.
        ;; This may cause builds to run slightly longer if the cache needs to be re-uploaded
        ;; so we may consider using the :script/end event to calculate credits instead.
        ;; TODO If still happens that build/end is never received by other components, so check
        ;; if it can happen that the event is never sent because the system has shut down too fast.
        (post-end-evt)
        (get-result)
        :exit-code)
    (catch Throwable ex
      (log/error "Failed to run controller" ex)
      (create-abort-file rt)
      (post-failure-evt rt (ex-message ex))
      err/error-process-failure)))

(defn script-exit
  "Event handler that stores the received event in the deferred, indicating that the
   script has actually terminated."
  [r ctx]
  (md/success! r (:event ctx))
  nil)

(defn filter-sid [sid]
  (emi/terminate-when ::filter-sid (comp (partial not= sid) :sid :event)))

(defn make-routes [sid r]
  ;; We could also wait for the build/end event to be received, which would give us
  ;; certainty that the event was actually posted.
  [[:script/end [{:handler (partial script-exit r)
                  ;; Only process events for this build
                  :interceptors [(filter-sid sid)]}]]])
