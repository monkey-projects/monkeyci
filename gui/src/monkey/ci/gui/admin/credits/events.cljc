(ns monkey.ci.gui.admin.credits.events
  (:require [re-frame.core :as rf]))

(rf/reg-event-fx
 :credits/customer-search
 (fn [ctx [_ details]]
   (let [f (-> details (get :customer-filter) first)]
     (println "Searching for customer:" f)
     ;; TODO
     )))
