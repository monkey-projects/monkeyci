(ns monkey.ci.gui.dashboard.views
  (:require [monkey.ci.gui.dashboard.events :as e]
            [monkey.ci.gui.dashboard.subs :as s]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.template :as t]
            [re-frame.core :as rf]))

(defn navbar []
  [:header.navbar.navbar-expand-lg.navbar-end.navbar-dark.bg-primary
   [:div.mx-2
    [:div.navbar-nav-wrap
     [:div.navbar-brand-wrapper
      [:img {:src (t/assets-url "/img/monkeyci-white.png") :style {:height "50px"}}]
      [:span.navbar-brand.text-light.fs-4.fw-bold "MonkeyCI"]
      [:span.ms-4.text-warning.fs-6 "Build your code in style"]]]]])

(defn repo-panel []
  [:h5 "Active Repos"])

(defn main-panel []
  (let [r (rf/subscribe [::s/recent-builds])]
    [:<>
     [:h3 "Dashboard"]
     [:p "There are " (count @r) " builds"]]))

(defn log-panel []
  [:p "Activity log"])

(defn dashboard [route]
  (rf/dispatch [::e/load-recent-builds (-> route r/path-params :org-id)])
  (fn [route]
    [:<>
     [navbar]
     [:div.m-2.d-flex.flex-row.gap-3
      [repo-panel]
      [:div.flex-grow-1
       [main-panel]]
      [log-panel]]]))
