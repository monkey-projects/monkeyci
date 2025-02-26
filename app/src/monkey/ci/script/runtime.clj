(ns monkey.ci.script.runtime
  "Functions for creating a runtime for build scripts"
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as art]
             [build :as b]
             [cache :as cache]
             [errors :as err]
             [runtime :as rt]
             [script :as s]
             [spec :as spec]]
            [monkey.ci.build.api :as api]
            [monkey.ci.script
             [config :as sc]
             [events :as se]]
            [monkey.ci.containers.build-api :as cba]
            [monkey.ci.events
             [build-api :as eba]
             [mailman :as em]]
            [monkey.ci.events.mailman
             [build-api :as emba]
             [bridge :as emb]]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.spec.script :as ss]
            [monkey.mailman.core :as mmc]))

(defn- client-url [{:keys [url port]}]
  (if port
    ;; Script child process always connects to localhost
    (format "http://localhost:%d" port)
    url))

(defn- new-api-client [config]
  (let [{:keys [token] :as ac} (sc/api config)]
    (api/make-client (client-url ac) token)))

(defn- new-events []
  (emb/->MailmanEventPoster nil))

(defn- new-artifacts []
  (art/make-build-api-repository nil))

(defn- new-cache []
  (cache/make-build-api-repository nil))

#_(defn- new-container-runner []
  (cba/map->BuildApiContainerRunner {}))

(defrecord EventStream [client]
  co/Lifecycle
  (start [this]
    (let [[stream close] (api/events-to-stream client)]
      (assoc this
             :stream stream
             :close close)))
  (stop [this]
    (when-let [s (:close this)]
      (s))
    (dissoc this :stream :close)))

(defn- new-event-stream []
  (->EventStream nil))

(defrecord BuildApiBrokerComponent [api-client event-stream broker]
  co/Lifecycle
  (start [this]
    (assoc this :broker (emba/make-broker api-client (:stream event-stream))))

  (stop [this]
    this)

  em/AddRouter
  (add-router [this routes opts]
    (mmc/add-listener (:broker this) (mmc/router routes opts))))

(defn- new-mailman []
  (map->BuildApiBrokerComponent {}))

(defn- new-routes [conf] 
  (letfn [(make-routes [c]
            (se/make-routes (assoc c
                                   :build (sc/build conf)
                                   :result (sc/result conf))))]
    (em/map->RouteComponent {:make-routes make-routes})))

(defn- using-api [obj]
  (co/using
   obj
   {:client :api-client}))

(defn make-system
  "Given a script configuration object, creates component system.  When started,
   it contains a fully configured `runtime` component that can be passed to the
   script functions."
  [config]
  {:pre [(spec/valid? ::ss/config config)]}
  (co/system-map
   :api-client (new-api-client config)
   :events     (co/using
                (new-events)
                {:broker :mailman})
   :event-stream (using-api (new-event-stream))
   :artifacts  (using-api (new-artifacts))
   :cache      (using-api (new-cache))
   ;; :containers (co/using
   ;;              (new-container-runner)
   ;;              {:client :api-client
   ;;               :bus :event-bus})
   ;; TODO Connect to the controller
   :mailman    (co/using
                (new-mailman)
                [:api-client :event-stream])
   :routes     (co/using
                (new-routes config)
                [:mailman :artifacts :cache :api-client :events])))

(def build :build)

(defn run-script
  "Starts the script runtime using given configuration and runs the script jobs.
   Returns a deferred that will hold the script status and result on completion."
  [config]
  (log/info "Running script with config:" config)
  (let [r (md/deferred)
        build (sc/build config)
        sys (-> config
                (sc/set-result r)
                (make-system)
                (co/start))]
    (em/post-events (:mailman sys) [(s/script-init-evt build (b/script-dir build))])
    (md/finally r #(co/stop sys))))

(defn- status->exit-code [{:keys [status]}]
  (if (= :success status)
    0
    err/error-script-failure))

(defn exit! [exit-code]
  (System/exit exit-code))

(defn run-script!
  "Loads and runs the script jobs.  Exits the process with a zero exit code on success."
  [{:keys [config]}]
  (try
    (-> (run-script config)
        (deref)
        (status->exit-code)
        (exit!))
    (catch Throwable ex
      (log/error "Failed to initialize script runtime" ex)
      (exit! err/error-process-failure))))
