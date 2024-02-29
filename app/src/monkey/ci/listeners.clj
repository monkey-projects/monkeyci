(ns monkey.ci.listeners
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [monkey.ci
             ;;[runtime :as rt]
             [storage :as st]]))

(defn- update-job [rt evt f & args]
  (apply st/patch-build-results
         (:storage rt)
         (:sid evt)
         update-in [:pipelines (get-in evt [:pipeline :index]) :jobs (:index evt)] f args))

(defn job-started [rt evt]
  (update-job rt evt merge {:start-time (:time evt)
                            :name (:name evt)}))

(defn job-completed [rt evt]
  (update-job rt evt merge {:end-time (:time evt)
                            :status (:status evt)}))

(defn save-build-result
  "Handles a `build/completed` event to store the result."
  [rt evt]
  (let [r (select-keys evt [:exit :result])]
    (log/debug "Saving build result:" r)
    (st/patch-build-results (:storage rt)
                            (get-in evt [:build :sid])
                            merge r)))

(defn build-update-handler
  "Handles a build update event.  Because many events may come in close proximity,
   we need to queue them to avoid losing data."
  [rt]
  (let [handlers {:job/start       job-started
                  :job/end         job-completed
                  :build/completed save-build-result}
        ch (ca/chan 10)
        dispatch-sub (fn [s dest]
                       (ca/go-loop [v (ca/<! s)]
                         (when v
                           (log/debug "Handling:" v)
                           (try
                             (dest rt v)
                             (catch Exception ex
                               ;; TODO Handle this better
                               (log/error "Unable to handle event" ex)))
                           (recur (ca/<! s)))))]
    ;; Naive implementation: process them in sequence.  This does not look 
    ;; to the sid for optimization, so it could be faster.
    (dispatch-sub ch (fn [rt evt]
                       (when-let [h (get handlers (:type evt))]
                         (h rt evt))))
    (fn [evt]
      (ca/put! ch evt)
      nil)))
