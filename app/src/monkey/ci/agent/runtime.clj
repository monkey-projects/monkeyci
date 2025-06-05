(ns monkey.ci.agent.runtime
  "Sets up the runtime that is used by a build agent."
  (:require [aleph.http :as http]
            [aleph.http.client-middleware :as ahmw]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [monkey.ci
             [artifacts :as a]
             [build :as b]
             [cache :as c]
             [edn :as edn]
             [protocols :as p]]
            [monkey.ci.agent
             [api-server :as aa]
             [events :as e]]
            [monkey.ci.build
             [api-server :as bas]
             [api :as ba]]
            [monkey.ci.containers
             [oci :as c-oci]
             [podman :as c-podman]]
            [monkey.ci.events
             [mailman :as em]
             [polling :as ep]]
            [monkey.ci.metrics.core :as mc]
            [monkey.ci.runners.runtime :as rr]
            [monkey.ci.web.auth :as auth]
            [monkey.mailman.core :as mmc]))

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
    ;; TODO Explicitly poll for build/queued events when capacity is available
    (em/map->RouteComponent {:make-routes make-routes})))

(defn- make-token [pk sid]
  (-> (auth/build-token sid)
      (auth/generate-and-sign-jwt pk)))

(defn- get-privkey [conf]
  (or (get-in conf [:api :private-key])
      (get-in conf [:jwk :priv])))

(defrecord ApiBuildParams [api-config pk]
  p/BuildParams
  (get-build-params [this build]
    (bas/get-params-from-api (assoc api-config :token (make-token pk (b/sid build))) build)))

(defn new-params [{:keys [api] :as config}]
  (->ApiBuildParams api (get-privkey config)))

(defn new-ssh-keys-fetcher [config]
  (let [client (ahmw/wrap-request http/request)]
    (fn [[cust-id repo-id :as sid]]
      (log/debug "Retrieving ssh keys for" sid)
      (-> {:url (format "%s/org/%s/repo/%s/ssh-keys"
                        (get-in config [:api :url])
                        cust-id
                        repo-id)
           :method :get
           :oauth-token (make-token (get-privkey config) sid)
           :accept "application/edn"}
          (client)
          (deref)
          :body
          (edn/edn->)))))

(defmulti make-container-routes :type)

(defmethod make-container-routes :oci [conf deps]
  (-> deps
      (assoc :oci conf)
      (c-oci/make-routes)))

(defmethod make-container-routes :podman [conf deps]
  (-> deps
      (merge conf)
      (update :artifacts a/make-blob-repository)
      (update :cache c/make-blob-repository)
      (c-podman/make-routes)))

(defmethod make-container-routes :noop [conf _]
  (log/info "Not handling container routes")
  [])

(defmethod make-container-routes :agent [conf _]
  (log/info "Using external agent for container jobs")
  [])

(defmethod make-container-routes :default [conf _]
  (log/warn "Unknown container runner type:" (:type conf))
  [])

(defn new-container-routes [config]
  (em/map->RouteComponent
   {:make-routes (fn [co]
                   (make-container-routes (:containers config) co))}))

(def global-to-local-events #{:build/queued :build/canceled})

(defn- max-builds-reached? [{:keys [builds config]}]
  (>= (count @builds) (:max-builds config)))

(defn new-poll-loop [conf]
  (let [defaults {:poll-interval 1000
                  :max-builds 1
                  :event-types #{:build/queued}}]
    (ep/map->PollLoop {:running? (atom false)
                       :config (->> (select-keys (:poll-loop conf) (keys defaults))
                                    (merge defaults))
                       :max-reached? max-builds-reached?})))

(defn new-poll-mailman
  "Sets up a mailman component that will be used by the poll loop.  It should only
   provide events for the poll loop, since the loop will stop polling as long as
   its capacity is reached."
  [conf]
  (em/make-component (:poll-loop conf)))

(defn make-system [conf]
  (letfn [(as-map [deps]
            (zipmap deps deps))]
    (co/system-map
     :builds (atom {})
     :artifacts (rr/new-artifacts conf)
     :cache (rr/new-cache conf)
     :workspace (rr/new-workspace conf)
     :event-stream (rr/new-event-stream)
     :git (rr/new-git)
     :ssh-keys-fetcher (new-ssh-keys-fetcher conf)
     :api-server (co/using
                  (new-api-server conf)
                  [:builds :artifacts :cache :workspace :mailman :event-stream :params :metrics])
     :mailman (rr/new-mailman conf)
     ;; Set up separate mailman to poll only the build/queued subject
     :poll-mailman (new-poll-mailman conf)
     :event-forwarder (co/using
                       (rr/new-event-stream-forwarder)
                       [:event-stream :mailman])
     :agent-routes (co/using
                    (new-agent-routes conf)
                    [:mailman :api-server :git :builds :workspace :ssh-keys-fetcher])
     ;; Poll loop that takes build/queued events, as long as there is capacity.
     :poll-loop (co/using
                 (new-poll-loop conf)
                 ;; Post outgoing events to different mailman, to avoid loops
                 {:builds :builds
                  :mailman :poll-mailman
                  :mailman-out :mailman
                  :routes :agent-routes})
     :params (new-params conf)
     :container-routes (co/using
                        (new-container-routes conf)
                        [:mailman :artifacts :cache :workspace :api-server])
     :metrics (mc/make-metrics))))

(defn- max-jobs-reached? [{:keys [state config]}]
  (let [c (c-podman/count-jobs @state)
        m (:max-jobs config)]
    (when (>= c m)
      (log/trace "Max jobs reached:" c ">=" m)
      true)))

(defn new-container-poll-loop [conf]
  (let [defaults {:poll-interval 1000
                  :max-jobs 1
                  :event-types #{:container/job-queued}}]
    (ep/map->PollLoop {:running? (atom false)
                       :config (->> (select-keys (:poll-loop conf) (keys defaults))
                                    (merge defaults))
                       :max-reached? max-jobs-reached?})))

(defn make-container-system
  "Creates a component system to be used by container agents"
  [conf]
  ;; Container agent always uses podman
  (let [conf (assoc-in conf [:containers :type] :podman)]
    (co/system-map
     :mailman (rr/new-mailman conf)
     :artifacts (rr/new-artifacts conf)
     :cache (rr/new-cache conf)
     :workspace (rr/new-workspace conf)
     :container-routes (co/using
                        (new-container-routes conf)
                        [:mailman :artifacts :cache :workspace :state])
     ;; Set up separate mailman to poll only the container/job-queued subject
     :poll-mailman (new-poll-mailman conf)
     :poll-loop (co/using
                 (new-container-poll-loop conf)
                 {:mailman :poll-mailman
                  :mailman-out :mailman
                  :routes :container-routes
                  :state :state})
     ;; Shared state between container routes and poll loop for capacity calculation
     :state (atom {}))))
