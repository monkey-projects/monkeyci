(ns monkey.ci.cli.run
  "Performs a single, local run of a script in a given directory.

   Starts the build API server, then launches the build script as a child
   process (via `clojure -X:monkeyci/build`).  The child process receives
   the API URL and token via environment variables so it can communicate
   with the server for params, events, artifacts and caches."
  (:require [babashka.fs :as fs]
            [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [monkey.ci.cli
             [config :as c]
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

(defn- evt-logger [evt]
  (log/debug "Processing events:" evt))

(defn setup-events [conf]
  (let [m (mmca/core-async-broker)
        routes (e/make-routes conf m)
        router (mmc/make-router routes {:executor mms/execute})]
    (mmc/add-listener m {:handler router})
    (mmc/add-listener m {:handler evt-logger})
    m))

(defn- link-broker-server-events
  "Ensures events get sent from the broker to the server channel and vice versa."
  [broker server]
  (let [p (ca/chan 1 (filter (comp (partial not= :cli) :src)))]
    (mmc/add-listener broker (fn [evt]
                               (ca/put! (:event-mult-ch server) (assoc evt :src :cli))
                               nil))
    (ca/pipe p (mmca/get-channel broker) false)
    (ca/tap (:event-mult server) p false)))

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
        result (or (c/get-ending conf) (promise))
        server (srv/start-server {:artifact-dir (fs/path sdir "artifacts")
                                  :cache-dir    (fs/path sdir "cache")})
        build {:checkout-dir dir}       ; TODO
        broker (-> conf
                   (c/set-api {:url (srv/server->url server)
                               :token (:token server)})
                   (c/set-build build)
                   (c/set-ending result)
                   (setup-events))]
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
