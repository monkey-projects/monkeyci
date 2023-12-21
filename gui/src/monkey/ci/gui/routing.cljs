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
   (println "Changing current route from" (:route/current db) "into" match)
   (assoc db :route/current match)))

(defonce router
  ;; Instead of pointing to the views directly, we refer to a keyword, which
  ;; is linked in another namespace (pages) to the actual view.  This allows
  ;; us to refer to the routing namespace from views, e.g. to resolve paths
  ;; by route names.
  (f/router
   [["/" :page/root]
    ["/login" :page/login]
    ["/c/:customer-id" :page/customer]
    ["/c/:customer-id/p/:project-id" :page/project]
    ["/c/:customer-id/p/:project-id/r/:repo-id" :page/repo]]))

(defn on-route-change [match history]
  (println "Route changed:" match)
  (rf/dispatch [:route/goto match]))

(defn start! []
  (rfe/start! router on-route-change {:use-fragment false}))

(defn path-for [id & [params]]
  (some-> (f/match-by-name router id params)
          :path))
