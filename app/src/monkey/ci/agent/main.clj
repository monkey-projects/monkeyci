(ns monkey.ci.agent.main
  "Main entry point for the build agent.  This is a process that picks up `build/queued`
   events and runs them as container child processes.  There is a single build api server,
   hosted by the agent process that serves all builds.  This allows to run the builds on
   one or more virtual machines, which is faster than provisioning containers for each
   build separately, albeit somewhat less efficient if there are few builds.  Because
   build scripts are mostly waiting for containers to finish, we can stack many parallel
   builds on one VM.

   It may be possible to suspend the build VMs after a certain inactivity timeout, to
   conserve computing capacity."
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [monkey.ci.agent.runtime :as ar]
            [monkey.ci.config :as c]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.web.http :as wh]))

(defn -main [& args]
  (try
    (log/info "Starting build agent")
    (rc/with-system
      (ar/make-system (c/load-config-file (first args)))
      (fn [sys]
        (wh/on-server-close (:api-server sys))))
    (finally
      (log/info "Build agent terminated."))))
