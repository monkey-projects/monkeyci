(ns monkey.ci.agent.runtime
  "Sets up the runtime that is used by a build agent."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [monkey.ci.agent
             [api-server :as aa]
             [events :as e]]
            [monkey.ci.containers
             [oci :as c-oci]
             [podman :as c-podman]]
            [monkey.ci.events.mailman :as em]
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
  (->ApiServer nil (:agent config)))

(defn new-agent-routes [config]
  (letfn [(make-routes [co]
            (e/make-routes (-> (:agent config)
                               (merge co))))]
    (em/map->RouteComponent {:make-routes make-routes})))

(defmulti make-container-routes :type)

(defmethod make-container-routes :oci [conf deps]
  (-> deps
      (assoc :oci conf)
      (c-oci/make-routes)))

(defmethod make-container-routes :podman [conf deps]
  ;; FIXME caches and artifacts should be an artifact repository, not a blob repo
  (-> deps
      (merge conf)
      (c-podman/make-routes)))

(defmethod make-container-routes :default [conf]
  (log/warn "Unknown container runner type:" (:type conf))
  {})

(defn new-container-routes [config]
  (em/map->RouteComponent
   {:make-routes (fn [co]
                   (make-container-routes (:containers config) co))}))

(def global-to-local-events #{:build/queued :build/canceled})

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
                [:builds :artifacts :cache :workspace :mailman :event-stream :params])
   :mailman (rr/new-local-mailman)
   :global-mailman (rr/new-mailman conf)
   :local-to-global (co/using
                     (rr/new-local-to-global-forwarder global-to-local-events)
                     [:global-mailman :mailman :event-stream])
   :global-to-local (co/using
                     (rr/global-to-local-routes global-to-local-events)
                     {:mailman :global-mailman
                      :local-mailman :mailman})
   :agent-routes (co/using
                  (new-agent-routes conf)
                  [:mailman :api-server :git :builds :workspace])
   :params (rr/new-params conf)
   :container-routes (co/using
                      (new-container-routes conf)
                      [:mailman :artifacts :cache :workspace :api-server])))
