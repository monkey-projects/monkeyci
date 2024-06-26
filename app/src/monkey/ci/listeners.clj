(ns monkey.ci.listeners
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.stream :as ms]
            [monkey.ci
             [build :as b]
             [runtime :as rt]
             [storage :as st]
             [utils :as u]]
            [monkey.ci.events.core :as ec]))

(defn- save-build
  "Saves the build in storage, then returns it"
  [storage sid build]
  (st/save-build storage build)
  (assoc build :sid sid))

(defn update-build [storage {:keys [sid build] :as evt}]
  (log/debug "Updating build:" sid)
  (let [existing (st/find-build storage sid)
        upd (-> (merge existing (dissoc build :script))
                (dissoc :sid :cleanup?))]
    (-> (save-build storage
                    sid
                    (cond-> upd
                      (= :build/end (:type evt))(assoc :credits (b/calc-credits upd))))
        (assoc :sid sid))))

(defn update-script [storage {:keys [sid script]}]
  (log/debug "Updating build script for sid" sid)
  (if-let [build (st/find-build storage sid)]
    (let [orig (get-in build [:script :jobs])]
      (save-build storage
                  sid
                  (assoc build
                         :script (u/deep-merge (:script build) script))))
    (log/warn "Build not found when updating script:" sid)))

(defn update-job [storage {:keys [sid job]}]
  (let [job-id (:id job)]
    (log/debug "Updating job for sid" sid ":" job-id)
    (if-let [build (st/find-build storage sid)]
      (save-build storage sid (assoc-in build [:script :jobs job-id] (update job :status #(or % :running))))
      (log/warn "Build not found when updating job:" sid))))

(def update-handlers
  {:job/start    update-job
   :job/updated  update-job
   :job/end      update-job
   :script/start update-script
   :script/end   update-script
   :build/start  update-build
   :build/end    update-build})

(defn build-update-handler
  "Handles a build update event.  Because many events may come in close proximity,
   we need to queue them to avoid losing data."
  [storage events]
  (let [stream (ms/stream 10)]
    ;; Naive implementation: process them in sequence.  This does not look 
    ;; to the sid for optimization, so it could be faster.
    (ms/consume (fn [evt]
                  (when-let [h (get update-handlers (:type evt))]
                    (log/debug "Handling:" evt)
                    (try
                      (when-let [build (h storage evt)]
                        ;; Dispatch consolidated build updated event
                        (ec/post-events events
                                        {:type :build/updated
                                         :sid (:sid evt)
                                         :build build}))
                      (catch Exception ex
                        ;; TODO Handle this better
                        (log/error "Unable to handle event" ex)))))
                stream)
    (fn [evt]
      (ms/put! stream evt)
      nil)))

(defrecord Listeners [events storage]
  co/Lifecycle
  (start [this]
    (if (every? nil? ((juxt :event-filter :handler) this))
      (let [ef {:types (set (keys update-handlers))}
            handler (build-update-handler storage events)]
        ;; Register listeners
        (ec/add-listener events ef handler)
        (assoc this :event-filter ef :handler handler))
      ;; If already registered, do nothing
      this))
  (stop [{:keys [event-filter handler] :as this}]
    (ec/remove-listener events event-filter handler)
    (dissoc this :event-filter :handler)))

(defmethod rt/setup-runtime :listeners [conf _]
  (when (and (= :server (:app-mode conf))
             (every? conf [:events :storage]))
    (log/debug "Setting up storage event listeners")
    (-> (map->Listeners {})
        (co/using [:events :storage]))))
