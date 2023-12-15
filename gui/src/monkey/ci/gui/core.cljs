(ns monkey.ci.gui.core
  (:require [monkey.ci.gui.events]
            [monkey.ci.gui.login.views :as lv]
            [reagent.core :as rc]
            [reagent.dom.client :as rd]
            [re-frame.core :as rf]))

(defn main []
  [:div
   [:h1 "MonkeyCI"]
   [:p "Welcome to MonkeyCI, the CI/CD tool that makes your life (and the planet) better!"]
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
    (rf/dispatch-sync [:initialize-db])
    (reload)))
