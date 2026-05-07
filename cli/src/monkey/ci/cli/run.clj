(ns monkey.ci.cli.run
  "Performs a single, local run of a script in a given directory.

   Starts the build API server, then launches the build script as a child
   process (via `clojure -X:monkeyci/build`).  The child process receives
   the API URL and token via environment variables so it can communicate
   with the server for params, events, artifacts and caches."
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [monkey.ci.cli
             [events :as e]
             [process :as proc]
             [server :as srv]
             [utils :as u]]
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

(defn setup-events [conf]
  (let [m (mmca/core-async-broker)
        routes (e/make-routes conf m)
        router (mmc/make-router routes {:executor mms/execute})]
    (mmc/add-listener m {:handler router})
    m))

(defn build
  "Runs a local build.

   Options (from CLI):
     :dir   — project root directory (default \".\")

   Starts a build API server on a random port, then invokes:
     clojure -X:monkeyci/build

   in the script directory, with MONKEYCI_API_URL and MONKEYCI_API_TOKEN
   set in the child process environment.

   Returns the exit code of the child process."
  [{:keys [dir] :or {dir "."} :as conf}]
  (let [sdir   (script-dir dir)
        broker (setup-events conf)
        server (srv/start-server {:artifact-dir (fs/path sdir "artifacts")
                                  :cache-dir    (fs/path sdir "cache")
                                  :channel (.chan broker)})]
    (log/info "Build API server started on port" (:port server))
    (try
      (let [url   (srv/server->url server)
            token (:token server)
            build {:checkout-dir dir} ; TODO
            exit  (run-build-process! sdir
                                      {api-url-env   url
                                       api-token-env token})]
        ;; Run the build by posting a :build/start event
        (mmc/post-events broker [(eb/build-start-evt build)])
        ;; TODO Wait for :build/end
        (log/info "Build process exited with code" exit)
        exit)
      (finally
        (srv/stop-server server)))))
