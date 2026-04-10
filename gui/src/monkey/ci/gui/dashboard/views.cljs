(ns monkey.ci.gui.dashboard.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.dashboard.events :as e]
            [monkey.ci.gui.dashboard.subs :as s]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.org.events]
            [monkey.ci.gui.template :as t]
            [monkey.ci.gui.time :as time]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn navbar []
  [:header.navbar.navbar-expand-lg.navbar-end.navbar-dark.bg-primary
   [:div.mx-2
    [:div.navbar-nav-wrap
     [:div.navbar-brand-wrapper
      [:img {:src (t/assets-url "/img/monkeyci-white.png") :style {:height "50px"}}]
      [:span.navbar-brand.text-light.fs-4.fw-bold "Monkey" [:span.text-warning "CI"]]
      [:span.ms-4.text-light.fs-6 "Build your code in style"]]]]])

(defn repo-panel []
  (let [r (rf/subscribe [::s/active-repos])]
    [:div.d-flex.flex-column.mt-2
     [:h5.mb-3 "Active Repos"]
     (->> @r
          (sort-by (comp :repo-name :repo))
          (map (fn [r]
                 [:div.text-nowrap.text-truncate
                  [:span.me-1 [:a {:href (r/path-for :page/repo (:repo r))} (get-in r [:repo :repo-name])]]
                  [:span.badge.bg-soft-primary.text-primary (:builds r)]]))
          (into [:div.d-flex.flex-column.gap-1]))]))

(defn- stats-card [contents]
  [:div.card.flex-fill.bg-soft-primary-light.text-primary
   [:div.card-body contents]])

(defn- successful-builds-stats []
  (let [s (rf/subscribe [::s/successful-builds])]
    [stats-card
     [:div.text-primary
      [:h4 (str (int (* 100 @s)) "%")]
      [:p "successful builds"]
      [co/progress-bar @s]]]))

(defn- avg-duration-stats []
  (let [a (rf/subscribe [::s/avg-duration])]
    [stats-card
     [:div.text-primary
      [:h4 (time/format-seconds (/ @a 1000))]
      [:p "average duration"]]]))

(defn- stats-row []
  [:div.d-flex.gap-4
   [successful-builds-stats]
   [avg-duration-stats]
   [stats-card
    [:div.text-primary
     [:h4 "84%"]
     [:p "avg. test coverage"]
     [co/progress-bar 0.84]]]
   [stats-card
    [:div.text-primary
     [:h4 "15"]
     [:p "failed jobs last 24h"]]]])

(defn- build-row [build]
  [:div.row.border-bottom.py-1.align-items-baseline
   [:div.col-4
    [:a {:href (r/path-for :page/build build)} "#" (:idx build)]
    [:div.small.text-nowrap.text-truncate (:repo-name build)]]
   [:div.col-2
    (time/reformat (:start-time build))]
   [:div.col-1
    (some-> (u/build-elapsed build)
            (/ 1000)
            (time/format-seconds))]
   [:div.col-3
    ;; TODO Calculate progress
    [co/progress-bar 1]]
   [:div.col-1
    [co/build-result (:status build)]]
   [:div.col-1
    ;; TODO Action buttons
    ]])

(defn- build-title-row []
  [:div.row.border-bottom.my-2.pb-1.fw-bold.border-2.border-dark
   [:div.col-4 "build / repo"]
   [:div.col-2 "start time"]
   [:div.col-1 "elapsed"]
   [:div.col-3 "progress"]
   [:div.col-1 "status"]
   [:div.col-1 "actions"]])

(defn main-panel [org-id]
  (let [r (rf/subscribe [::s/recent-builds])]
    [:div.d-flex.flex-column.gap-3.mt-2
     [:div.d-flex
      [:h3.flex-1 "Dashboard"]
      [:div.ms-auto [co/reload-btn-sm [::e/load-recent-builds org-id]]]]
     [stats-row]
     [:div.container-xxl.mx-2
      (->> @r
           (map build-row)
           (into [:<>
                  [build-title-row]]))]]))

(defn log-panel []
  [:h5.mt-3 "Activity log"])

(defn dashboard [route]
  (let [org-id (-> route r/path-params :org-id)]
    (rf/dispatch [::e/load-recent-builds org-id])
    (rf/dispatch [:org/load org-id])
    (fn [route]
      [:div.vh-100
       [navbar]
       [:div.gap-3.container-fluid
        [:div.row.h-100
         [:div.col-2.border-end
          [:div.d-flex.flex-column.gap-3
           [repo-panel]
           [:div.border-top
            [log-panel]]]]
         [:div.col
          [main-panel org-id]]
         #_[:div.col-2.border-start
          [log-panel]]]]])))
