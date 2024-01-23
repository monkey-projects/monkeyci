(ns monkey.ci.gui.routing
  (:require [re-frame.core :as rf]
            [reitit.frontend :as f]
            [reitit.frontend.easy :as rfe]))

(rf/reg-sub
 :route/current
 (fn [db _]
   (:route/current db)))

(rf/reg-event-db
 :route/changed
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
    ["/c/:customer-id/r/:repo-id" :page/repo]
    ["/c/:customer-id/r/:repo-id/b/:build-id" :page/build]
    ["/github/callback" :page/github-callback]]))

(defn on-route-change [match history]
  (println "Route changed:" match)
  (rf/dispatch [:route/changed match]))

(defn start! []
  (rfe/start! router on-route-change {:use-fragment false}))

(defn path-for [id & [params]]
  (some-> (f/match-by-name router id params)
          :path))

(def path-params (comp :path :parameters))

(defn set-path! [p]
  (set! (.-pathname js/location) p))

(rf/reg-fx
 :route/goto
 (fn [p]
   (set-path! p)))

(rf/reg-event-fx
 :route/goto
 (fn [_ [_ p]]
   {:route/goto p}))
