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
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [protocols :as p]
             [spec :as s]
             [utils :as u]]))

(defmulti setup-runtime (fn [_ k] k))

(defmethod setup-runtime :default [_ k]
  {})

(defn config->runtime
  "Creates the runtime from the normalized config map"
  [conf]
  ;; TODO Re-enable this but allow for more flexible checks
  #_{:pre  [(spec/valid? ::s/app-config conf)]
     :post [(spec/valid? ::s/runtime %)]}
  ;; Apply each of the discovered runtime setup implementations
  (let [m (-> (methods setup-runtime)
              (dissoc :default)
              (keys))]
    (-> (reduce (fn [r k]
                  (assoc r k (setup-runtime conf k)))
                {}
                m)
        (assoc :config conf))))

(defn start
  "Starts the runtime by starting all parts as a component tree.  Returns a
   component system that can be passed to `stop`."
  [rt]
  (log/info "Starting runtime system")
  (->> rt
       (mc/filter-vals some?)
       ;; TODO Check if we should create a separate system from the runtime instead of this
       (co/map->SystemMap)
       (co/start-system)))

(defn stop
  "Stops a previously started runtime"
  [rt]
  (log/info "Stopping runtime system")
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
(def runner :runner)
(def ^:deprecated build "Gets build info from runtime" :build)

(defn events-receiver [{:keys [events]}]
  (if (satisfies? p/EventReceiver events)
    events
    ;; Backwards compatibility, mostly in tests
    (:receiver events)))

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
               (config->runtime)
               (start))]
    (try
      (let [v (f rt)]
        (cond-> v
          (md/deferred? v) deref))
      (finally (stop rt)))))

(defmacro with-runtime
  "Convenience macro that wraps `with-runtime-fn` by binding runtime to `r` and 
   invoking the body."
  [conf mode r & body]
  `(with-runtime-fn ~conf ~mode
     (fn [~r]
       ~@body)))

(defn- prepare-events [evt]
  (letfn [(add-time [evt]
            (update evt :time #(or % (u/now))))]
    (->> (u/->seq evt)
         (map add-time))))

(defn post-events
  "Posts one or more events using the event poster in the runtime"
  [{:keys [events]} evt]
  (let [evt (prepare-events evt)]
    (cond
      (satisfies? p/EventPoster events)
      (p/post-events events evt)
      ;; For backwards compatibility, in tests
      (fn? (:poster events))
      ((:poster events) evt)
      :else
      (log/warn "No event poster configured"))))

(defn rt->config
  "Returns a map that can be serialized to `edn`.  This is used
   to pass application configuration to child processes or containers."
  [rt]
  ;; Return the original, non-normalized configuration
  (-> rt
      :config
      (merge (select-keys rt [:build]))
      ;; Child processes never start an event server
      (mc/update-existing :events dissoc :server)))

(defn ^:deprecated update-build
  "Updates the build in the runtime by applying `f` with given args."
  [rt f & args]
  (apply update rt :build f args))
