(ns monkey.ci.listeners
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [monkey.ci
             [runtime :as rt]
             [storage :as st]]
            [monkey.ci.events.core :as ec]))

(defn- save-build
  "Saves the build in storage, then returns it"
  [storage build]
  (st/save-build storage build)
  build)

(defn update-build [storage {:keys [sid build]}]
  (log/debug "Updating build:" sid)
  (let [existing (st/find-build storage sid)]
    (save-build storage
                (-> (merge existing (dissoc build :script))
                    (dissoc :sid :cleanup?)))))

(defn update-script [storage {:keys [sid script]}]
  (log/debug "Updating build script for sid" sid)
  (if-let [build (st/find-build storage sid)]
    (let [orig (get-in build [:script :jobs])]
      (save-build storage
                  (assoc build
                         :script (cond-> script
                                   orig (assoc :jobs orig)))))
    (log/warn "Build not found when updating script:" sid)))

(defn update-job [storage {:keys [sid job]}]
  (let [job-id (:id job)]
    (log/debug "Updating job for sid" sid ":" job-id)
    (if-let [build (st/find-build storage sid)]
      (save-build storage (assoc-in build [:script :jobs job-id] job))
      (log/warn "Build not found when updating job:" sid))))

(def update-handlers
  {:job/start    update-job
   :job/end      update-job
   :script/start update-script
   :script/end   update-script
   :build/start  update-build
   :build/end    update-build})

(defn build-update-handler
  "Handles a build update event.  Because many events may come in close proximity,
   we need to queue them to avoid losing data."
  [storage]
  (let [ch (ca/chan 10)
        dispatch-sub (fn [s dest]
                       (ca/go-loop [v (ca/<! s)]
                         (when v
                           (log/debug "Handling:" v)
                           (try
                             (dest storage v)
                             (catch Exception ex
                               ;; TODO Handle this better
                               (log/error "Unable to handle event" ex)))
                           (recur (ca/<! s)))))]
    ;; Naive implementation: process them in sequence.  This does not look 
    ;; to the sid for optimization, so it could be faster.
    (dispatch-sub ch (fn [st evt]
                       ;; TODO Re-dispatch a build update event that contains all info grouped,
                       ;; useful for clients.
                       (when-let [h (get update-handlers (:type evt))]
                         (h st evt))))
    (fn [evt]
      (ca/put! ch evt)
      nil)))

(defrecord Listeners [events storage]
  co/Lifecycle
  (start [this]
    (if (every? nil? ((juxt :event-filter :handler) this))
      (let [ef {:types (set (keys update-handlers))}
            handler (build-update-handler storage)]
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
