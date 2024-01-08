(ns monkey.ci.listeners
  (:require [clojure.tools.logging :as log]
            [monkey.ci.storage :as st]))

(defn pipeline-started [ctx evt]
  (st/patch-build-results
   (:storage ctx)
   (:sid evt)
   assoc-in [:pipelines (:pipeline evt)] {:start-time (:time evt)}))

(defn pipeline-completed [ctx evt]
  (st/patch-build-results
   (:storage ctx)
   (:sid evt)
   update-in [:pipelines (:pipeline evt)] merge {:end-time (:time evt)
                                                 :status (:status evt)}))

(defn save-build-result
  "Handles a `build/completed` event to store the result."
  [ctx evt]
  (let [r (select-keys evt [:exit :result])]
    (log/debug "Saving build result:" r)
    (st/patch-build-results (:storage ctx)
                            (get-in evt [:build :sid])
                            merge r)))
