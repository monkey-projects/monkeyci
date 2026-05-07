(ns monkey.ci.events.mailman.interceptors
  "Generic mailman interceptors"
  (:require [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci.errors :as errors]
            [monkey.ci.events.builders :as b]))

(def get-result :result)

(defn set-result [ctx r]
  (assoc ctx :result r))

(def no-result
  "Interceptor that empties result"
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
   :error (fn [{:keys [event] :as ctx} ex]
            (log/error "Failed to handle event" (:type event) ", marking build as failed" ex)
            (set-result ctx (b/build-end-evt (-> (:build event)
                                                 (assoc :message (ex-message ex)))
                                             errors/error-process-failure)))})
