(ns monkey.ci.components
  "Defines components for system startup and integration"
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as c]
            [medley.core :as mc]
            [monkey.ci
             [commands :as co]
             [config :as config]
             [events :as e]
             [listeners :as li]
             [logging :as l]
             [git :as git]
             [process :as p]
             [reporting :as rep]
             [runners :as r]
             [storage :as st]]
            [monkey.ci.reporting.print]
            [monkey.ci.runners.oci]
            [monkey.ci.storage
             [cached :as cs]
             [file]
             [oci]]
            [monkey.ci.web
             [auth :as auth]
             [github :as wg]
             [handler :as web]
             [script-api :as wsa]]))

(defn- call-and-dissoc [c key f]
  (when-let [x (key c)]
    (f x))
  (dissoc c key))

(defrecord BusComponent []
  c/Lifecycle
  (start [this]
    (log/debug "Creating event bus")
    (merge this (e/make-bus)))
  
  (stop [this]
    (log/debug "Stopping event bus")
    (e/close-bus this)
    (dissoc this :pub :channel)))

(defn new-bus []
  (->BusComponent))

(defrecord HttpServer [context]
  c/Lifecycle
  (start [this]
    ;; Alternatively we could just initialize the handler here and
    ;; let commmands/http-server actually start it.
    (assoc this :server (web/start-server context)))

  (stop [this]
    (call-and-dissoc this :server web/stop-server)))

(defn new-http-server []
  (map->HttpServer {}))

(def default-context
  {:git {:fn (fn default-git-clone [opts]
               (git/clone+checkout opts)
               ;; Return the checkout dir
               (:dir opts))}})

(defrecord Context [command config event-bus storage]
  c/Lifecycle
  (start [this]
    (log/debug "Creating application context using configuration" config)
    (-> this
        (merge default-context)
        (dissoc :config)
        (merge config)
        (assoc :runner (r/make-runner config)
               :storage (:storage storage)
               :public-api wsa/local-api
               :reporter (rep/make-reporter (:reporter config))
               ;; TODO Load keys using config
               :jwk (auth/keypair->ctx (auth/generate-keypair)))
        (config/configure-workspace)
        (config/configure-cache)
        (config/initialize-log-maker)
        (config/initialize-log-retriever)))
  (stop [this]
    this))

(defn new-context [cmd]
  (map->Context {:command cmd}))

(defn ctx-async
  "Creates an event handler fn that invokes `f` in a separate thread with
   the context added to the event."
  [ctx f]
  (fn [evt]
    (ca/thread
      (try
        (f (e/with-ctx ctx evt))
        (catch Exception ex
          (log/error "Failed to handle event" evt ex))))))

(defn logger
  "Event handler that just logs the message"
  [evt]
  (log/info (:message evt)))

(defn register-handlers [ctx bus]
  (let [update-handler (li/build-update-handler ctx)]
    (->> {:webhook/validated (ctx-async ctx r/build)
          :build/triggered (ctx-async ctx r/build)
          :build/completed update-handler
          :script/start logger
          :script/end logger
          :pipeline/start (juxt logger update-handler)
          :pipeline/end (juxt logger update-handler)
          :step/start (juxt logger update-handler)
          :step/end (juxt logger update-handler)}
         (map (partial apply e/register-handler bus)))))

(defn register-tx-handlers [ctx bus]
  (->> {:webhook/github (comp (map (partial wg/prepare-build ctx))
                              (remove nil?))}
       (map (partial apply e/register-pipeline bus))))

(defrecord Listeners [bus context]
  c/Lifecycle
  (start [this]
    (->> (concat (register-handlers context bus)
                 (register-tx-handlers context bus))
         (doall)
         (assoc this :handlers)))
  
  (stop [this]
    (call-and-dissoc
     this :handlers (comp doall
                          (partial map (partial e/unregister-handler bus))))))

(defn new-listeners []
  (map->Listeners {}))

(defrecord Storage [config]
  c/Lifecycle
  (start [this]
    ;; Use cached storage
    (assoc this :storage (cs/make-cached-storage (st/make-storage config))))
  
  (stop [this]
    (dissoc this :storage)))

(defn new-storage []
  (->Storage nil))
