(ns monkey.ci.cli.run
  "Performs a single, local run of a script in a given directory.

   Starts the build API server, then launches the build script as a child
   process (via `clojure -X:monkeyci/build`).  The child process receives
   the API URL and token via environment variables so it can communicate
   with the server for params, events, artifacts and caches."
  (:require [babashka.fs :as fs]
            [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [monkey.ci.time :as t]
            [monkey.ci.cli
             [config :as c]
             [events :as e]
             [process :as proc]
             [print-events :as pe]
             [server :as srv]
             [utils :as u]
             [version :as v]]
            [monkey.ci.cli.containers.podman :as podman]
            [monkey.ci.events.builders :as eb]
            [monkey.mailman
             [core :as mmc]
             [core-async :as mmca]
             [sieppari :as mms]]))

(def api-url-env  "MONKEYCI_API_URL")
(def api-token-env "MONKEYCI_API_TOKEN")

(defn- script-dir
  "Returns the script directory to run. Prefers .monkeyci/ inside dir."
  [dir]
  (u/find-script-dir dir))

(defn run-build-process! [sdir env]
  (proc/run ["clojure" "-X:monkeyci/build"] sdir env))

(defn- evt-logger [evt]
  (log/debug "Processing events:" evt))

(defn- make-router [routes]
  (mmc/make-router routes {:executor mms/execute}))

(defn- make-podman-routes [conf mailman]
  (let [state (atom {})]
    (podman/make-routes {:work-dir (str (c/get-jobs-dir conf))
                         :mailman  mailman
                         :state    state
                         :podman   (c/get-podman conf)
                         :cleanup? (not (c/get-no-clean conf))})))

(defn- add-routes-listener [mailman routes]
  (mmc/add-listener mailman {:handler (make-router routes)}))

(defn setup-events [conf]
  (log/debug "Setting up events with config:" conf)
  (let [m (mmca/core-async-broker)]
    (doto m
      (mmc/add-listener {:handler evt-logger})
      (add-routes-listener (e/make-routes conf m))
      (add-routes-listener (make-podman-routes conf m))
      (add-routes-listener (pe/make-routes conf)))))

(defn- link-broker-server-events
  "Ensures events get sent from the broker to the server channel and vice versa."
  [broker server]
  (let [p (ca/chan 1 (filter (comp (partial not= :cli) :src)))]
    (mmc/add-listener broker {:handler (fn [evt]
                                         (ca/put! (:event-mult-ch server) (assoc evt :src :cli))
                                         nil)})
    (ca/pipe p (mmca/get-channel broker) false)
    (ca/tap (:event-mult server) p false)))

(defn- args->conf [args]
  {:lib-coords {:mvn/version (or (:lib-version args) v/version)}})

(defn build
  "Runs a local build.

   Options (from CLI):
     :dir      — project root directory (default \".\")
     :no-clean — when truthy, the workspace directory is NOT deleted after
                 the build completes (useful for debugging container jobs)

   Creates a temporary work directory that holds the workspace copy, artifacts,
   cache and log files for this build.  Starts a build API server on a random
   port, triggers the build via a `:build/pending` event, waits for completion,
   then stops the server.  The work directory (including workspace) is deleted
   on exit unless `--no-clean` was supplied.

   Returns the exit code of the child process."
  [{:keys [dir no-clean] :or {dir "."} :as conf}]
  (let [dir      (str (fs/absolutize dir))
        sdir     (script-dir dir)
        work-dir (fs/create-temp-dir {:prefix "monkeyci-"})
        result   (or (c/get-ending conf) (promise))
        run-conf (-> (args->conf conf)
                     (c/set-work-dir work-dir)
                     (c/set-no-clean no-clean))
        server   (srv/start-server
                  {:artifact-dir (c/get-artifact-dir run-conf)
                   :cache-dir    (c/get-cache-dir run-conf)
                   :workspace-file (c/get-workspace run-conf)})
        build    {:checkout-dir dir
                  :org-id       "local-org"
                  :repo-id      (fs/file-name (fs/cwd))
                  :build-id     (str "local-build-" (t/now))}
        broker   (-> run-conf
                     (c/set-api {:url   (srv/server->url server)
                                 :token (:token server)})
                     (c/set-build build)
                     (c/set-ending result)
                     (setup-events))]
    (log/info "Work directory:" (str work-dir))
    (try
      (link-broker-server-events broker server)
      (log/info "Build API server started on port" (:port server))
      ;; Trigger the build by posting a :build/pending event
      (mmc/post-events broker [(eb/build-pending-evt build)])
      (log/debug "Build/pending event posted")
      ;; Wait for build end
      (let [exit @result]
        (log/info "Build process exited with code" exit)
        exit)
      (finally
        (srv/stop-server server)))))
