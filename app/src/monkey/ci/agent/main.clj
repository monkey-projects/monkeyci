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
            [monkey.ci
             [config :as c]
             ;; Enable additional reader tags
             [edn]]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.web.http :as wh]))

(defn run-agent [conf waiter]
  (log/info "Starting build agent")
  (rc/with-system-async
    (ar/make-system conf)
    (fn [{:keys [api-server] :as sys}]
      (log/debug "API server started at port" (:port api-server))
      (waiter sys))))

(defn -main [& args]
  @(run-agent (c/load-config-file (first args))
              (comp wh/on-server-close :api-server)))
