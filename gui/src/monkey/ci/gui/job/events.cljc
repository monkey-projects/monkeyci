(ns monkey.ci.gui.job.events
  (:require [re-frame.core :as rf]))

(def alerts ::alerts)

(rf/reg-event-fx
 :job/init
 (fn [_ _]
   ;; TODO
   ))
