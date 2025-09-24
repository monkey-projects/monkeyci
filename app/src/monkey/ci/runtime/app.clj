(ns monkey.ci.runtime.app
  "Functions for setting up a runtime for application (cli or server)"
  (:require [buddy.core.codecs :as bcc]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.bus :as mb]
            [monkey.ci
             [blob :as blob]
             [build :as b]
             [oci :as oci]
             [reporting :as rep]
             [storage :as s]
             [vault :as v]]
            [monkey.ci.events.mailman :as em]
            [monkey.ci.events.mailman
             [db :as emd]
             [interceptors :as emi]
             [jms :as emj]]
            [monkey.ci.metrics
             [core :as m]
             [events :as me]
             [otlp :as mo]]
            [monkey.ci.reporting.print]
            [monkey.ci.runners.oci :as ro]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.storage.sql :as sql]
            [monkey.ci.vault
             [common :as vc]
             [scw :as v-scw]]
            [monkey.ci.web
             [handler :as wh]
             [http :as http]]
            [monkey.oci.container-instance.core :as ci]))

(defn- as-map [deps]
  (zipmap deps deps))

(defn- new-artifacts [config]
  (blob/make-blob-store config :artifacts))

(defn- new-cache [config]
  (blob/make-blob-store config :cache))

(defn- new-build-cache [config]
  ;; The build cache is separate from the customer cache, because the build cache
  ;; is (mostly) out of control of the user, where the customer cache is fully
  ;; determined by the user's cache configurations.
  (blob/make-blob-store config :build-cache))

(defn- new-workspace [config]
  (blob/make-blob-store config :workspace))

(defn- new-metrics []
  (m/make-metrics))

(defn- new-metrics-routes []
  (em/map->RouteComponent
   {:make-routes (fn [c]
                   (me/make-routes (get-in c [:metrics :registry])))}))

(defn new-mailman
  "Creates new mailman event broker component."
  [config]
  (em/make-component (:mailman config)))

(defn- new-db-pool [{conf :storage}]
  (if (= :sql (:type conf))
    (sql/pool-component conf)
    {}))

(defn- new-db-migrator [{conf :storage}]
  (if (= :sql (:type conf))
    (sql/migrations-component)
    {}))

(defn- new-storage [config]
  (if (not-empty (:storage config))
    (s/make-storage config)
    (s/make-memory-storage)))

(defn- new-http-server [config]
  (http/->HttpServer (:http config) nil))

(defrecord HttpApp [runtime]
  co/Lifecycle
  (start [this]
    (assoc this :handler (wh/make-app runtime)))
  
  (stop [this]
    this))

(defn- new-http-app []
  (->HttpApp nil))

(defn- new-reporter [conf]
  (rep/make-reporter (:reporter conf)))

(defn- new-jwk [conf]
  ;; Return a map because component doesn't allow nils
  (select-keys conf [:jwk]))

(defn- new-vault [config]
  (v/make-vault (:vault config)))

(defmulti dek-utils
  "Creates DEK functions: 
     - `:generator`: A 0-arity function that generates new data encryption keys.  Returns both the encrypted (for storage) and unencrypted key.
     - `decrypter`: A 1-arity function that decrypts encrypted keys."
  :type)

(defmethod dek-utils :default [_]
  (zipmap [:generator :decrypter] (repeat (constantly nil))))

(defmethod dek-utils :scw [conf]
  (let [client (v-scw/make-client conf)]
    {:generator (comp deref #(v-scw/generate-dek client))
     :decrypter (comp deref #(v-scw/decrypt-dek client %))}))

(defrecord Crypto [config]
  co/Lifecycle
  (start [this]
    (let [{dg :generator kd :decrypter} (dek-utils config)
          ;; TODO Replace with real cache (using clojure.core.cache lib)
          cache (or (:cache this) (atom {}))]
      (letfn [(lookup-dek [org-id]
                (log/debug "Looking up data encryption key for" org-id)
                (let [enc (some-> (s/find-crypto (:storage this) org-id)
                                  :dek)
                      plain (kd enc)]
                  (log/debug "Looked up encrypted key for" org-id ":" enc)
                  (swap! cache assoc org-id {:enc enc
                                             :key plain})
                  (bcc/b64->bytes plain)))
              (get-dek [org-id]
                (or (some-> (get-in @cache [org-id :key])
                            (bcc/b64->bytes))
                    (lookup-dek org-id)))]
        (assoc this
               :dek-generator
               (fn [org-id]
                 (let [k (dg)]
                   (swap! cache assoc org-id k)
                   k))
               :encrypter
               (fn [v org-id cuid]
                 (vc/encrypt (get-dek org-id) (v/cuid->iv cuid) v))
               :decrypter
               (fn [v org-id cuid]
                 (vc/decrypt (get-dek org-id) (v/cuid->iv cuid) v))))))

  (stop [this]
    this))

(defn new-crypto
  "Creates functions for data encryption, such as a new data encryption key
   generator function, which is used to create new keys as needed."
  [conf]
  (->Crypto (:dek conf)))

(defrecord ServerRuntime [config]
  co/Lifecycle
  (start [this]
    (-> this
        (assoc :jwk (get-in this [:jwk :jwk])
               :metrics (get-in this [:metrics :registry]))))

  (stop [this]
    this))

(defn- new-server-runtime [conf]
  (->ServerRuntime conf))

(defrecord ProcessReaper [config]
  clojure.lang.IFn
  (invoke [this]
    (let [{:keys [containers] :as rc} (:runner config)]
      (if (#{:oci} (:type rc))
        (oci/delete-stale-instances (ci/make-context containers) (:compartment-id containers))
        []))))

(defn- new-process-reaper [conf]
  (->ProcessReaper conf))

(defmulti make-queue-options :type)

(defmethod make-queue-options :jms [conf]
  ;; Make sure to read from queues, not topics to avoid duplicate
  ;; processing when multiple replicas
  {:destinations (emj/queue-destinations conf)})

(defmethod make-queue-options :nats [conf]
  ;; Use a queue or stream, if so configured
  (-> (select-keys conf [:queue])
      (merge (:db conf))))

(defmethod make-queue-options :default [_]
  {})

(defn new-queue-options
  "Configures messaging queues.  This is implementation specific, so it differs depending
   on the mailman broker type."
  [conf]
  (make-queue-options (:mailman conf)))

(defn new-app-routes [conf]
  (letfn [(make-routes [{:keys [storage update-bus]}]
            (emd/make-routes storage update-bus))]
    (em/map->RouteComponent {:make-routes make-routes})))

(defn new-update-routes []
  (letfn [(make-routes [c]
            [[:build/updated [{:handler (constantly nil)
                               :interceptors [(emi/update-bus (:update-bus c))]}]]])]
    (em/map->RouteComponent {:make-routes make-routes})))

(defmulti make-server-runner (comp :type :runner))

(defmethod make-server-runner :oci [config]
  (letfn [(make-routes [c]
            (ro/make-routes (:runner config)
                            (:storage c)
                            (:vault c)))]
    (em/map->RouteComponent {:make-routes make-routes})))

(defmethod make-server-runner :agent [_]
  ;; Agent is a separate process, so don't do anything here.
  (log/debug "Agent runner configured, make sure one or more agents are running.")
  {})

;; TODO Add other runners

(defmethod make-server-runner :default [_]
  {})

(defn- new-server-runner [config]
  (make-server-runner config))

(defrecord OtlpClient [config metrics]
  co/Lifecycle
  (start [this]
    (log/info "Pushing metrics to OpenTelemetry endpoint:" (:url config))
    (assoc this :client (mo/make-client (:url config)
                                        (:registry metrics)
                                        config)))

  (stop [{:keys [client] :as this}]
    (when client
      (.close client))
    (dissoc this :client)))

(defn new-otlp-client [{:keys [otlp]}]
  (if (not-empty otlp)
    (->OtlpClient otlp nil)
    {}))

(defn make-server-system
  "Creates a component system that can be used to start an application server."
  [config]
  (co/system-map
   :artifacts (new-artifacts config)
   :http      (co/using
               (new-http-server config)
               {:app :http-app})
   :http-app  (co/using
               (new-http-app)
               [:runtime])
   :runner    (co/using
               (new-server-runner config)
               (-> (as-map [:storage :vault :mailman])
                   (assoc :options :queue-options)))
   :runtime   (co/using
               (new-server-runtime config)
               [:artifacts :metrics :storage :jwk :process-reaper :vault :mailman :update-bus
                :crypto])
   :pool      (new-db-pool config)
   :migrator  (co/using
               (new-db-migrator config)
               [:pool :vault :crypto])
   :storage   (co/using
               (new-storage config)
               [:pool])
   :jwk       (new-jwk config)
   :metrics   (co/using
               (new-metrics)
               [:storage])
   :metrics-routes (co/using
                    (new-metrics-routes)
                    [:metrics :mailman])
   :process-reaper (new-process-reaper config)
   :vault     (new-vault config)
   :crypto    (co/using
               (new-crypto config)
               [:storage])
   :mailman   (new-mailman config)
   :queue-options (new-queue-options config)
   :mailman-routes (co/using
                    (new-app-routes config)
                    (-> (as-map [:mailman :storage])
                        (assoc :options :queue-options)))
   :update-routes (co/using
                   (new-update-routes)
                   [:mailman :update-bus])
   :update-bus (mb/event-bus)
   :otlp (co/using
          (new-otlp-client config)
          [:metrics])))

(defn with-server-system [config f]
  (rc/with-system (make-server-system config) f))

(defn make-cli-system
  "Creates a component system that can be used by CLI commands"
  [config]
  (co/system-map
   :runtime (co/using
             {:config config}
             [:reporter])
   :reporter (new-reporter config)))

(defn with-cli-runtime [config f]
  (rc/with-runtime (make-cli-system config) f))
