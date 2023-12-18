(ns monkey.ci.gui.routing
  (:require [re-frame.core :as rf]
            [reitit.frontend :as f]
            [reitit.frontend.easy :as rfe]))

(rf/reg-sub
 :route/current
 (fn [db _]
   (:route/current db)))

(rf/reg-event-db
 :route/goto
 (fn [db [_ match]]
   (assoc db :route/current match)))

(def router (f/router [["/" :page/root]
                       ["/login" :page/login]]))

(defn on-route-change [match history]
  (println "Route changed:" match)
  (rf/dispatch [:route/goto match]))

(defn start! []
  (rfe/start! router on-route-change {:use-fragment false}))
