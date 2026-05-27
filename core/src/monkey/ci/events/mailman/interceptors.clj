(ns monkey.ci.events.mailman.interceptors
  "Generic mailman interceptors"
  (:require [babashka.process :as bp]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.build.core :as bc]
            [monkey.ci.errors :as errors]
            [monkey.ci.events.builders :as b]
            [monkey.ci.time :as t]))

(def get-result :result)

(defn set-result [ctx r]
  (assoc ctx :result r))

(def no-result
  "Interceptor that empties result"
  {:name ::no-result
   :leave #(dissoc % :result)})

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

(def get-mailman ::mailman)

(defn set-mailman [ctx e]
  (assoc ctx ::mailman e))

(defn add-mailman
  "Adds mailman component to the context"
  [mm]
  {:name ::add-mailman
   :enter #(set-mailman % mm)})

(def handle-build-error
  "Marks build as failed"
  {:name ::build-error-handler
   :error (fn build-error [{:keys [event] :as ctx} ex]
            (log/error "Failed to handle event" (:type event) ", marking build as failed" ex)
            (set-result ctx (b/build-end-evt (-> (:build event)
                                                 (assoc :message (ex-message ex)))
                                             errors/error-process-failure)))})

(def handle-job-error
  "Marks job as failed on error."
  {:name ::handle-job-error
   :error (fn job-error [{ex :error :as ctx}]
            (let [{:keys [job-id sid] :as e} (:event ctx)]
              (log/error "Got error while handling event" e "for job" job-id ex)
              (set-result ctx
                          [(b/job-end-evt job-id sid
                                          (-> bc/failure
                                              (bc/with-message (ex-message ex))))])))})

;;;; ─── Process lifecycle ────────────────────────────────────────────────────

(def get-process ::process)

(defn set-process [ctx ws]
  (assoc ctx ::process ws))

(def start-process
  "Starts a child process using the command map stored in the context result.
   GraalVM-compatible: uses babashka.process directly."
  {:name ::start-process
   :leave (fn start-proc [ctx]
            (let [cmd (get-result ctx)]
              (log/debug "Starting child process:" cmd)
              (cond-> ctx
                cmd (set-process (bp/process cmd)))))})

;;;; ─── Job context ──────────────────────────────────────────────────────────

(def get-job-ctx ::job-ctx)

(defn set-job-ctx [ctx jc]
  (assoc ctx ::job-ctx jc))

(defn update-job-ctx [ctx f & args]
  (apply update ctx ::job-ctx f args))

;;;; ─── Termination ─────────────────────────────────────────────────────────

(defn terminate-when
  "Interceptor that terminates the chain (without error) when `pred` returns
   truthy.  Sets `:terminated true` in the context so callers can inspect it.

   GraalVM-compatible: does NOT use io.pedestal.interceptor.chain/terminate —
   the sieppari executor used by the CLI honours `:terminated true`."
  [id pred]
  {:name id
   :enter (fn [ctx]
            (cond-> ctx
              (pred ctx) (assoc :terminated true)))})
