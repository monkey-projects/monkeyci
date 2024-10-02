(ns monkey.ci.listeners
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.stream :as ms]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [jobs :as j]
             [runtime :as rt]
             [storage :as st]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.spec :as spec]
            [monkey.ci.spec.events :as se]))

(defn- save-build
  "Saves the build in storage, then returns it"
  [storage sid build]
  (st/save-build storage build)
  (assoc build :sid sid))

(defn pending-build [storage {:keys [sid build] :as evt}]
  (save-build storage
              sid
              (assoc build :status :pending)))

(defn init-build [storage {:keys [sid build] :as evt}]
  (save-build storage
              sid
              (assoc build :status :initializing)))

(defn patch-build
  "Patches the existing build, by merging it with `patch`, which is either
   a map, or a function that takes the existing build and returns the patch map.
   Returns the updated build, or `nil` if the build was not found."
  [storage sid patch]
  (log/debug "Patching build:" sid)
  (if-let [existing (st/find-build storage sid)]
    (save-build storage
                sid
                (-> (merge existing (if (fn? patch)
                                      (patch existing)
                                      patch))))
    (log/warn "Unable to patch build" sid ", record not found in db (initialize event missed?)")))

(defn start-build [storage {:keys [sid time credit-multiplier]}]
  (patch-build storage sid {:start-time time
                            :credit-multiplier credit-multiplier
                            :status :running}))

(defn end-build [storage {:keys [sid time status message]}]
  (patch-build storage sid (fn [build]
                             {:end-time time
                              :status status
                              :credits (b/calc-credits build)
                              :message message})))

(defn cancel-build [storage {:keys [sid] :as evt}]
  (patch-build storage
               sid
               {:status :canceled}))

(defn init-script [storage {:keys [sid script-dir]}]
  (patch-build storage sid #(assoc-in % [:script :script-dir] script-dir)))

(defn start-script [storage {:keys [sid jobs]}]
  (patch-build storage sid
               (fn [build]
                 (assoc-in build [:script :jobs] (->> jobs
                                                      (map #(vector (j/job-id %) %))
                                                      (into {}))))))

(defn end-script [storage {:keys [sid status]}]
  (patch-build storage sid #(assoc-in % [:script :status] status)))

(defn- patch-job [storage {:keys [sid job-id]} patch]
  (patch-build storage sid
               (fn [build]
                 (update-in build [:script :jobs job-id]
                            (if (fn? patch)
                              patch
                              #(merge % patch))))))

(defn init-job [storage {:keys [credit-multiplier] :as evt}]
  (patch-job storage evt {:status :initializing
                          :credit-multiplier credit-multiplier}))

(defn start-job [storage {:keys [time] :as evt}]
  (patch-job storage evt {:status :running
                          :start-time time}))

(defn skip-job [storage {:keys [time] :as evt}]
  (patch-job storage evt {:status :skipped}))

(defn end-job [storage {:keys [time] :as evt}]
  (patch-job storage evt (-> evt
                             (select-keys [:status :result])
                             (assoc :end-time time))))

(def update-handlers
  {:build/pending       pending-build
   :build/initializing  init-build
   :build/start         start-build
   :build/end           end-build
   :build/canceled      cancel-build
   :script/initializing init-script
   :script/start        start-script
   :script/end          end-script
   :job/initializing    init-job
   :job/start           start-job
   :job/skipped         skip-job
   ;; :job/executed is ignored, we wait for the actual end
   :job/end             end-job})

(defn handle-event [evt storage events]
  (spec/valid? ::se/event evt)
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

(defn build-update-handler
  "Handles a build update event.  Because many events may come in close proximity,
   we need to queue them to avoid losing data."
  [storage events]
  (let [stream (ms/stream 10)]
    ;; Naive implementation: process them in sequence.  This does not look 
    ;; to the sid for optimization, so it could be faster.
    (ms/consume #(handle-event % storage events)
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
