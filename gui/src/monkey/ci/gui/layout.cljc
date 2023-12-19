(ns monkey.ci.gui.layout
  (:require [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn curr-route []
  (let [r (rf/subscribe [:route/current])]
    [:div.alert.alert-info
     [:div "Current route: " [:b (str (some-> @r :data :name))]]]))

(defn header []
  [:div.header
   [:h1 "MonkeyCI"]
   [:p.lead "Welcome to MonkeyCI, the CI/CD tool that makes your life (and the planet) better!"]])

(defn footer []
  [:div.footer
   [:p "built by " [:a {:href "https://www.monkey-projects.be"} "Monkey Projects"]]])

(defn welcome
  "Renders welcome panel with the subpanel as a child"
  [subpanel]
  [:div
   [header]
   [curr-route]
   [:div.row
    [:div.col
     [u/logo]]
    [:div.col
     subpanel]]
   [footer]])

(defn default [subpanel]
  [:div
   [header]
   subpanel
   [footer]])
