(ns monkey.ci.events.mailman.interceptors
  "General purpose interceptors"
  (:require [babashka.process :as bp]
            [clojure.tools.logging :as log]
            [io.pedestal.interceptor.chain :as pi]
            [manifold
             [bus :as mb]
             [deferred :as md]]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [errors :as errors]
             [time :as t]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events
             [builders :as eb]
             [core :as ec]]
            [monkey.mailman.core :as mmc]))

(def get-result :result)

(defn set-result [ctx r]
  (assoc ctx :result r))

(def add-time
  {:name ::add-evt-time
   :leave (letfn [(set-time [evt]
                    (update evt :time #(or % (t/now))))]
            (fn [ctx]
              (update ctx :result (partial map set-time))))})

(def trace-evt
  "Logs event info, for debugging purposes."
  {:name ::trace
   :enter (fn [ctx]
            (log/trace "Incoming event:" (:event ctx))
            ctx)
   :leave (fn [ctx]
            (log/trace "Result from handling" (get-in ctx [:event :type]) ":" (get-result ctx))
            ctx)})

(def no-result
  "Empties result"
  {:name ::no-result
   :leave #(dissoc % :result)})

(def get-state ::state)

(defn set-state [ctx q]
  (assoc ctx ::state q))

(defn update-state [ctx f & args]
  (apply update ctx ::state f args))

(defn with-state
  "Interceptor that keeps track of a global state object in the context.
   The updated state is `deep-merge`d into the global state."
  [state]
  {:name ::state
   :enter (fn [ctx]
            (set-state ctx @state))
   :leave (fn [ctx]
            (swap! state mc/deep-merge (get-state ctx))
            ctx)})

(def handle-build-error
  "Marks build as failed"
  {:name ::build-error-handler
   :error (fn [{:keys [event] :as ctx} ex]
            (log/error "Failed to handle event" (:type event) ", marking build as failed" ex)
            (set-result ctx (b/build-end-evt (-> (:build event)
                                                 (assoc :message (ex-message ex)))
                                             errors/error-process-failure)))})

(def handle-job-error
  {:name ::handle-job-error
   :error (fn [ctx ex]
            (let [{:keys [job-id sid] :as e} (:event ctx)]
              (log/error "Got error while handling event" e ex)
              (set-result ctx
                          [(eb/job-end-evt job-id sid (-> bc/failure
                                                          (bc/with-message (ex-message ex))))])))})

(defn update-bus [bus]
  "Publishes the event to the given manifold bus"
  {:name ::update-bus
   :enter (fn [{:keys [event] :as ctx}]
            (mb/publish! bus (:type event) event)
            ctx)})

(defn realize-deferred [d]
  "Interceptor that takes a deferred and realizes it on leave with the result
   specified in the context."
  {:name ::realize-deferred
   :leave (fn [ctx]
            (md/success! d (get-result ctx))
            ctx)})

(def get-process ::process)

(defn set-process [ctx ws]
  (assoc ctx ::process ws))

(def start-process
  "Starts a child process using the command line stored in the result"
  {:name ::start-process
   :leave (fn [ctx]
            (let [cmd (get-result ctx)]
              (log/debug "Starting child process:" cmd)
              (cond-> ctx
                ;; TODO See if we can replace bp with clojure.java.process
                cmd (set-process (bp/process cmd)))))})

(def get-mailman ::mailman)

(defn set-mailman [ctx e]
  (assoc ctx ::mailman e))

(defn add-mailman
  "Adds mailman component to the context"
  [mm]
  {:name ::add-mailman
   :enter #(set-mailman % mm)})

(def get-job-ctx ::job-ctx)

(defn set-job-ctx [ctx jc]
  (assoc ctx ::job-ctx jc))

(defn update-job-ctx [ctx f & args]
  (apply update ctx ::job-ctx f args))

(defn add-job-ctx [jc]
  {:name ::add-job-ctx
   :enter #(set-job-ctx % jc)})

(defn add-job-to-ctx [get-job]
  "Interceptor that adds the job indicated in the event (by job-id) to the job context.
   This is required by jobs and extensions to be present."
  {:name ::add-job-to-ctx
   :enter (fn [ctx]
            (let [job (get-job ctx)]
              (update-job-ctx ctx assoc :job job)))})

(defn terminate-when [id pred]
  "Interceptor that terminates when given predicate is truthy"
  {:name id
   :enter (fn [ctx]
            (cond-> ctx
              (pred ctx) (pi/terminate)))})

(defn forwarder
  "Interceptor that forwards events to another broker"
  [id dest]
  {:name id
   :enter (fn [ctx]
            (mmc/post-events (:broker dest) [(:event ctx)])
            ctx)})

(def get-db ::db)

(defn set-db [ctx db]
  (assoc ctx ::db db))

(defn use-db
  "Adds storage to the context as ::db"
  [db]
  {:name ::use-db
   :enter #(set-db % db)})
