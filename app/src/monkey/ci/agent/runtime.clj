(ns monkey.ci.agent.runtime
  "Sets up the runtime that is used by a build agent."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [monkey.ci.agent.api-server :as aa]
            [monkey.ci.runners.runtime :as rr]))

(defrecord ApiServer [builds api-config]
  co/Lifecycle
  (start [this]
    (merge this (aa/start-server (merge this (select-keys api-config [:port])))))

  (stop [this]
    (when-let [srv (:server this)]
      (log/debug "Shutting down API server")
      (.close srv))))

(defn new-api-server [config]
  (map->ApiServer (select-keys config [:api-config])))

(defn make-system [conf]
  (co/system-map
   :builds (atom {})
   :artifacts (rr/new-artifacts conf)
   :cache (rr/new-cache conf)
   :workspace (rr/new-workspace conf)
   :event-stream (rr/new-event-stream)
   :git (rr/new-git)
   :api-server (co/using
                (new-api-server conf)
                [:builds :artifacts :cache :workspace :mailman :event-stream])
   :mailman (rr/new-local-mailman)
   :global-mailman (rr/new-mailman conf)
   :local-to-global (co/using
                     (rr/new-local-to-global-forwarder)
                     [:global-mailman :mailman :event-stream])
   :global-to-local (co/using
                     (rr/global-to-local-routes #{:build/queued :build/canceled})
                     {:mailman :global-mailman
                      :local-mailman :mailman})))
