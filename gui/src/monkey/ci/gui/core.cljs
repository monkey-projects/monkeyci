(ns monkey.ci.gui.core
  (:require [monkey.ci.gui.events]
            [monkey.ci.gui.login.views :as lv]
            [monkey.ci.gui.routing :as routing]
            [reagent.core :as rc]
            [reagent.dom.client :as rd]
            [re-frame.core :as rf]))

(defn curr-route []
  (let [r (rf/subscribe [:route/current])]
    [:p "Current route: " (str (some-> @r :data :name))]))

(defn main []
  [:div
   [:h1 "MonkeyCI"]
   [:p "Welcome to MonkeyCI, the CI/CD tool that makes your life (and the planet) better!"]
   [curr-route]
   [:div.row
    [:div.col
     [:img.img-fluid {:src "/img/monkeyci-large.png"}]]
    [:div.col
     [lv/login-form]]]])

(defonce app-root (atom nil))

(defn ^:dev/after-load reload []
  (rd/render @app-root [main]))

(defn init []
  (let [root (rd/create-root (.getElementById js/document "root"))]
    (reset! app-root root)
    (routing/start!)
    (rf/dispatch-sync [:initialize-db])
    (reload)))
