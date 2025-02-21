(ns monkey.ci.events.mailman.interceptors
  "General purpose interceptors"
  (:require [clojure.tools.logging :as log]
            [io.pedestal.interceptor.chain :as pi]
            [manifold.bus :as mb]
            [meta-merge.core :as mm]
            [monkey.ci
             [build :as b]
             [errors :as errors]
             [time :as t]]))

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
            (log/trace "Result from handling" (get-in ctx [:event :type]) ":" (:result ctx))
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
   The updated state is `meta-merge`d into the global state."
  [state]
  {:name ::state
   :enter (fn [ctx]
            (set-state ctx @state))
   :leave (fn [ctx]
            (swap! state mm/meta-merge (get-state ctx))
            ctx)})

(def handle-build-error
  "Marks build as failed"
  {:name ::build-error-handler
   :error (fn [{:keys [event] :as ctx} ex]
            (log/error "Failed to handle event" (:type event) ", marking build as failed" ex)
            (-> ctx
                (assoc :result (b/build-end-evt (-> (:build event)
                                                    (assoc :message (ex-message ex)))
                                                errors/error-process-failure))
                ;; Remove the error to ensure leave chain is processed
                (dissoc ::pi/error)))})

(defn update-bus [bus]
  "Publishes the event to the given manifold bus"
  {:name ::update-bus
   :enter (fn [{:keys [event] :as ctx}]
            (mb/publish! bus (:type event) event)
            ctx)})
