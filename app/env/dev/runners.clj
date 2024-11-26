(ns runners
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [config :as co]
            [manifold.deferred :as md]
            [monkey.ci
             [build :as b]
             [commands :as cmd]
             [process :as proc]
             [runners]
             [script :as s]]
            [monkey.ci.build.api-server :as bas]
            [monkey.ci.runtime
             [app :as ra]
             [script :as rs]]
            [monkey.ci.web.auth :as auth]))

(defn clear-git-dir [build]
  (when-let [d (get-in build [:git :dir])]
    (when (fs/exists? d)
      (fs/delete-tree d))))

(defn gen-token [conf]
  (let [keypair (auth/config->keypair conf)
        ctx {:jwk keypair}
        token (auth/build-token (b/sid (:build conf)))]
    (auth/generate-jwt-from-rt ctx token)))

(defn- add-token [conf]
  (assoc-in conf [:api :token] (gen-token conf)))

(defn run-build-local
  "Runs the given build locally, using the global config.  This means that the local
   runner will be used, but it may run any container jobs or fetch/store artifacts,
   etc, as configured."
  [build]
  (let [dir (str (fs/create-temp-dir))
        conf (-> @co/global-config
                 (assoc-in [:runner :type] :local)
                 (assoc :build build
                        :dev-mode true
                        :checkout-base-dir dir)
                 (add-token))]
    (clear-git-dir build)
    ;; FIXME Doesn't work if the container sidecars can't connect to the build api server
    (cmd/run-build conf)))

(defn make-build [sid git-url branch]
  (-> (zipmap [:customer-id :repo-id :build-id] sid)
      (assoc :sid sid
             :git {:url git-url
                   :branch branch})))

(defn- example-build [dir]
  (-> ["example-cust" "example-repo" (str "build-" (System/currentTimeMillis))]
      (make-build nil nil)
      (dissoc :git)
      (assoc-in [:script :script-dir] (str (fs/absolutize (fs/path "examples/" dir))))))

(defn run-example-local
  "Runs an example build locally by starting the build process"
  [example]
  (run-build-local (example-build example)))

(defn- run-controller [run-file rt]
  ;; Prepare workspace
  ;; Check out cache
  ;; Create start file
  ;; Wait for script to create stop file
  ;; Upload new cache, if changed
  (log/info "Running controller, creating run file")
  (fs/create-file run-file)
  (while (fs/exists? run-file)
    (Thread/sleep 1000))
  (log/info "Script finished"))

(defn- run-script [run-file config]
  (log/info "Running script with config:" config)
  (while (not (fs/exists? run-file)) 
    (Thread/sleep 1000))
  (log/info "Run file created, starting script")
  (rs/with-runtime config
    (fn [rt]
      (let [ns *ns*]
        (try
          (s/exec-script! rt)
          (finally
            (fs/delete run-file)
            (in-ns (ns-name ns))))))))

(defn run-oci2-local
  "Runs an oci 2 test by starting two threads: one for the controller
   and another one for the build script."
  [config example]
  (let [build (example-build example)
        api-config {:port 3002
                    :token (bas/generate-token)}
        run-path (fs/path (fs/temp-dir) (str (:build-id build) ".run"))]
    (log/info "Using run file:" run-path)
    (md/future
      (ra/with-runner-system (assoc config
                                    :build build
                                    :runner {:type :noop
                                             :api-port (:port api-config)
                                             :api-token (:token api-config)})
        (fn [rt]
          (run-controller run-path rt))))
    (run-script run-path (proc/child-config build api-config))))
