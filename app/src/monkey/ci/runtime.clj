(ns monkey.ci.runtime
  "The runtime can be considered the 'live configuration'.  It is created
   from the configuration, and is passed on to the application modules.  The
   runtime provides the information (often in the form of functions) needed
   by the modules to perform work.  This allows us to change application 
   behaviour depending on configuration, but also when testing.

   Thie namespace also provides some utility functions for working with the
   context.  This is more stable than reading properties from the runtime 
   directly."
  (:require [clojure.spec.alpha :as spec]
            [com.stuartsierra.component :as co]
            [monkey.ci.spec :as s]))

(def initial-runtime
  {})

(defmulti setup-runtime (fn [_ k] k))

(defmethod setup-runtime :default [_ k]
  (get initial-runtime k))

(defn config->runtime
  "Creates the runtime from the normalized config map"
  ;; TODO Add mode parameter
  [conf]
  {:pre  [(spec/valid? ::s/app-config conf)]
   ;;:post [(spec/valid? ::s/runtime %)]
   }
  (-> (reduce-kv (fn [r k v]
                   (assoc r k (setup-runtime conf k)))
                 initial-runtime
                 conf)
      (assoc :config conf)))

(defn start
  "Starts the runtime by starting all parts as a component tree.  Returns a
   component system that can be passed to `stop`."
  [rt]
  (-> (co/map->SystemMap rt)
      (co/start-system)))

(defn stop
  "Stops a previously started runtime"
  [rt]
  (co/stop-system rt))

;;; Accessors and utilities

(def config :config)

(defn from-config [k]
  (comp k config))

(def app-mode (from-config :app-mode))
(def cli-mode? (comp (partial = :cli) app-mode))
(def server-mode? (comp (partial = :server) app-mode))

(def account (from-config :account))
(def args (from-config :args))
(def reporter :reporter)
(def api-url (comp :url account))
(def log-maker (comp :maker :logging))
(def log-retriever (comp :retriever :logging))
(def work-dir (from-config :work-dir))
(def dev-mode? (from-config :dev-mode))
(def ssh-keys-dir (from-config :ssh-keys-dir))
(def events-receiver (comp :receiver :events))
(def runner :runner)

(defn get-arg [rt k]
  (k (args rt)))

(defn report
  "Reports `obj` to the user with the reporter from the runtime."
  [rt obj]
  (when-let [r (reporter rt)]
    (r obj)))

(defn with-runtime-fn
  "Creates a runtime for the given mode (server, cli, script) from the specified 
   configuration and passes it to `f`."
  [conf mode f]
  (let [rt (-> conf
               (assoc :app-mode mode)
               (config->runtime))]
    ;; TODO Start/stop runtime
    (f rt)))

(defmacro with-runtime
  "Convenience macro that wraps `with-runtime-fn` by binding runtime to `r` and 
   invoking the body."
  [conf mode r & body]
  `(with-runtime-fn ~conf ~mode
     (fn [~r]
       ~@body)))

(defn post-events
  "Posts one or more events using the event poster in the runtime"
  [rt evt]
  (when-let [p (get-in rt [:events :poster])]
    (p evt)))
