(ns monkey.ci.listeners
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [manifold.stream :as ms]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [jobs :as j]
             [storage :as st]
             [time :as t]]
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
  ;; Read and update build in one operation to avoid concurrency issues.
  ;; Use an atom to return the updated build without having to re-fetch it from db.
  (let [upd (atom nil)]
    (if (st/update-build
         storage sid
         (fn [existing]
           (reset! upd
                   (merge existing (if (fn? patch)
                                     (patch existing)
                                     patch)))))
      (assoc @upd :sid sid)
      (log/warn "Unable to patch build" sid ", record not found in db (initialize or pending event missed?)"))))

(defn start-build [storage {:keys [sid time credit-multiplier]}]
  (patch-build storage sid {:start-time time
                            :credit-multiplier credit-multiplier
                            :status :running}))

(defn- create-credit-consumption [{:keys [credits customer-id] :as build} storage]
  (when (and (some? credits) (pos? credits))
    (let [avail (st/list-available-credits storage customer-id)]
      ;; TODO To avoid problems when there are no available credits at this point, we should
      ;; consider "reserving" one at the start of the build.  We have to do a check at that
      ;; point anyway.
      (if (empty? avail)
        (log/warn "No available customer credits for build" (:sid build))
        (st/save-credit-consumption storage
                                    (-> (select-keys build [:customer-id :repo-id :build-id])
                                        (assoc :amount credits
                                               :consumed-at (t/now)
                                               :credit-id (-> avail first :id)))))))
  build)

(defn end-build [storage {:keys [sid time status message]}]
  (-> (patch-build
       storage sid
       (fn [build]
         (-> {:end-time time
              :status status
              ;; TODO Calculate credits on each job update
              :credits (b/calc-credits build)}
             (mc/assoc-some :message message))))
      (create-credit-consumption storage)))

(defn cancel-build [storage {:keys [sid]}]
  (-> (patch-build storage
                   sid
                   (fn [build]
                     {:status :canceled
                      :credits (b/calc-credits build)}))
      ;; Register consumed credits also in case of cancellation, for all jobs that have already run
      (create-credit-consumption storage)))

(defn init-script [storage {:keys [sid script-dir]}]
  (patch-build storage sid #(assoc-in % [:script :script-dir] script-dir)))

(defn start-script [storage {:keys [sid jobs]}]
  (patch-build storage sid
               (fn [build]
                 (assoc-in build [:script :jobs] (->> jobs
                                                      (map #(vector (j/job-id %) %))
                                                      (into {}))))))

(defn end-script [storage {:keys [sid status message]}]
  (patch-build storage sid (fn [b]
                             (-> b
                                 (assoc-in [:script :status] status)
                                 (mc/assoc-some :message message)))))

(defn- patch-job [storage {:keys [sid job-id]} patch]
  ;; TODO Update job atomically in storage
  (when-let [job (st/find-job storage (concat sid [job-id]))]
    (log/debug "Patching job:" job)
    (st/save-job storage
                 sid
                 (if (fn? patch)
                   (patch job)
                   (merge job patch)))
    ;; Re-read the build for return
    (st/find-build storage sid)))

(defn init-job [storage {:keys [credit-multiplier] :as evt}]
  (patch-job storage evt {:status :initializing
                          :credit-multiplier credit-multiplier}))

(defn start-job [storage {:keys [time] :as evt}]
  (patch-job storage evt (-> {:status :running
                              :start-time time}
                             (merge (select-keys evt [:credit-multiplier])))))

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
      (st/with-transaction storage tx
        (when-let [build (h tx evt)]
          ;; Dispatch consolidated build updated event
          (ec/post-events events
                          {:type :build/updated
                           :sid (:sid evt)
                           :build build})))
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
