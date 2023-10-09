(ns monkey.ci.components
  "Defines components for system startup and integration"
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as c]
            [monkey.ci
             [commands :as co]
             [events :as e]
             [git :as git]
             [process :as p]
             [runners :as r]
             [storage :as st]]
            [monkey.ci.web
             [github :as wg]
             [handler :as web]]))

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
               (->> ((juxt :url :branch :id :dir) opts)
                    (apply git/clone+checkout))
               ;; Return the checkout dir
               (:dir opts))}})

(defrecord Context [command config event-bus]
  c/Lifecycle
  (start [this]
    (-> this
        (merge default-context)
        (merge config)
        (dissoc :config)
        (update :runner r/make-runner)))
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
      (f (e/with-ctx ctx evt)))))

(defn logger [evt]
  (log/info (:message evt)))

(defrecord Listeners [bus context]
  c/Lifecycle
  (start [this]
    (->> {:webhook/github (ctx-async context wg/build)
          :script/start logger
          :script/end logger
          :pipeline/start logger
          :pipeline/end logger
          :step/start logger
          :step/end logger}
         (map (partial apply e/register-handler bus))
         (doall)
         (assoc this :handlers)))
  
  (stop [this]
    (call-and-dissoc
     this :handlers (comp doall
                          (partial map (partial e/unregister-handler bus))))))

(defn new-listeners []
  (map->Listeners {}))

(defrecord Storage [context]
  c/Lifecycle
  (start [this]
    (assoc this :storage (st/make-storage (:storage context))))
  
  (stop [this]
    (dissoc this :storage)))

(defn new-storage [ctx]
  (->Storage ctx))
