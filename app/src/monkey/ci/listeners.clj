(ns monkey.ci.listeners
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [monkey.ci
             [runtime :as rt]
             [storage :as st]]
            [monkey.ci.events.core :as ec]))

(defn update-build [storage {:keys [sid build]}]
  (log/debug "Updating build:" sid)
  (let [existing (st/find-build storage sid)]
    (st/save-build storage
                   (-> (merge existing (dissoc build :script))
                       (dissoc :sid :cleanup?)))))

(defn update-script [storage {:keys [sid script]}]
  (log/debug "Updating build script for sid" sid)
  (if-let [build (st/find-build storage sid)]
    (let [orig (get-in build [:script :jobs])]
      (st/save-build storage
                     (assoc build
                            :script (cond-> script
                                      orig (assoc :jobs orig)))))
    (log/warn "Build not found when updating script:" sid)))

(defn update-job [storage {:keys [sid job]}]
  (let [job-id (:id job)]
    (log/debug "Updating job for sid" sid ":" job-id)
    (if-let [build (st/find-build storage sid)]
      (st/save-build storage (assoc-in build [:script :jobs job-id] job))
      (log/warn "Build not found when updating job:" sid))))

(defn build-update-handler
  "Handles a build update event.  Because many events may come in close proximity,
   we need to queue them to avoid losing data."
  [storage]
  (let [handlers {:job/start    update-job
                  :job/end      update-job
                  :script/start update-script
                  :script/end   update-script
                  :build/start  update-build
                  :build/end    update-build}
        ch (ca/chan 10)
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
                       (when-let [h (get handlers (:type evt))]
                         (h st evt))))
    (fn [evt]
      (ca/put! ch evt)
      nil)))

(defrecord Listeners [events storage]
  co/Lifecycle
  (start [this]
    ;; Register listeners
    (ec/add-listener events (build-update-handler storage))
    this)
  (stop [this]))

(defmethod rt/setup-runtime :listeners [conf _]
  (when (every? conf [:events :storage])
    (log/debug "Setting up storage event listeners")
    (-> (map->Listeners {})
        (co/using [:events :storage]))))
