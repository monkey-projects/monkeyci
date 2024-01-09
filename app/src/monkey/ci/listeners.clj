(ns monkey.ci.listeners
  (:require [clojure.tools.logging :as log]
            [monkey.ci.storage :as st]))

;; FIXNE These listeners are invoked asynchronously.  It could happen that
;; multiple handlers modify the same file, in which case one will overwrite
;; the changes of the other.

(defn- update-pipeline [ctx evt f & args]
  (apply st/patch-build-results
         (:storage ctx)
         (:sid evt)
         update-in [:pipelines (get-in evt [:pipeline :index])] f args))

(defn pipeline-started [ctx evt]
  (update-pipeline ctx evt merge {:start-time (:time evt)
                                  :name (get-in evt [:pipeline :name])}))

(defn pipeline-completed [ctx evt]
  (update-pipeline ctx evt merge {:end-time (:time evt)
                                  :status (:status evt)}))

(defn- update-step [ctx evt f & args]
  (apply st/patch-build-results
         (:storage ctx)
         (:sid evt)
         update-in [:pipelines (get-in evt [:pipeline :index]) :steps (:index evt)] f args))

(defn step-started [ctx evt]
  (update-step ctx evt merge {:start-time (:time evt)
                              :name (:name evt)}))

(defn step-completed [ctx evt]
  (update-step ctx evt merge {:end-time (:time evt)
                              :status (:status evt)}))

(defn save-build-result
  "Handles a `build/completed` event to store the result."
  [ctx evt]
  (let [r (select-keys evt [:exit :result])]
    (log/debug "Saving build result:" r)
    (st/patch-build-results (:storage ctx)
                            (get-in evt [:build :sid])
                            merge r)))
