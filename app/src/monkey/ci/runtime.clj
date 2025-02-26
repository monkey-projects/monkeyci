(ns monkey.ci.runtime
  "The runtime can be considered the 'live configuration'.  It is created
   from the configuration, and is passed on to the application modules.  The
   runtime provides the information (often in the form of functions) needed
   by the modules to perform work.  This allows us to change application 
   behaviour depending on configuration, but also when testing.

   This namespace also provides some utility functions for working with the
   runtime.  This is more stable than reading properties from the runtime 
   directly.

   This namespace is being phased out in favor of passing specific information
   to components directly, since passing around too much information is an
   antipattern."
  (:require [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [protocols :as p]
             [utils :as u]]))

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
(def runner :runner)
(def ^:deprecated build "Gets build info from runtime" :build)

(def runner-api-port (from-config (comp :port :api :runner)))
(def artifacts :artifacts)

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

(defn- prepare-events [evt]
  (letfn [(add-time [evt]
            (update evt :time #(or % (u/now))))]
    (->> (u/->seq evt)
         (map add-time))))

(defn post-events
  "Posts one or more events using the event poster in the runtime"
  [{:keys [events]} evt]
  (let [evt (prepare-events evt)]
    (log/trace "Posting events:" evt)
    (cond
      (satisfies? p/EventPoster events)
      (p/post-events events evt)
      ;; For backwards compatibility, in tests
      (fn? (:poster events))
      ((:poster events) evt)
      :else
      (log/warn "No event poster configured"))))
