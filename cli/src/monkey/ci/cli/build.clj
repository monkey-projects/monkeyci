(ns monkey.ci.cli.build
  "Performs a single, local build of a script in a given directory.

   Starts the build API server, then launches the build script as a child
   process (via `clojure -X:monkeyci/build`).  The child process receives
   the API URL and token via environment variables so it can communicate
   with the server for params, events, artifacts and caches."
  (:require [clojure.tools.logging :as log]
            [monkey.ci.cli
             [process :as proc]
             [server :as srv]
             [utils :as u]]))

(def api-url-env  "MONKEYCI_API_URL")
(def api-token-env "MONKEYCI_API_TOKEN")

(defn- script-dir
  "Returns the script directory to run. Prefers .monkeyci/ inside dir."
  [dir]
  (u/find-script-dir dir))

(defn run-build-process! [sdir env]
  (proc/run ["clojure" "-X:monkeyci/build"] sdir env))

(defn build
  "Runs a local build.

   Options (from CLI):
     :dir   — project root directory (default \".\")

   Starts a build API server on a random port, then invokes:
     clojure -X:monkeyci/build

   in the script directory, with MONKEYCI_API_URL and MONKEYCI_API_TOKEN
   set in the child process environment.

   Returns the exit code of the child process."
  [{:keys [dir] :or {dir "."}}]
  (let [sdir   (script-dir dir)
        server (srv/start-server {:artifact-dir (str sdir "/artifacts")
                                  :cache-dir    (str sdir "/cache")})]
    (log/info "Build API server started on port" (:port server))
    (try
      (let [url      (srv/server->url server)
            token    (:token server)
            exit     (run-build-process! sdir
                                         {api-url-env   url
                                          api-token-env token})]
        (Thread/sleep 2000)
        (log/info "Build process exited with code" exit)
        exit)
      (finally
        (srv/stop-server server)))))
