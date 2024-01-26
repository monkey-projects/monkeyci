(ns monkey.ci.gui.layout
  (:require [monkey.ci.gui.components :as co]
            [re-frame.core :as rf]))

(defn header []
  [:div.header
   [:div.row
    [:div.col-1
     [:div.mt-2 [:a {:href "/"} [co/logo]]]]
    [:div.col-11
     [:h1 "MonkeyCI"]
     [:p.lead "Welcome to MonkeyCI, the CI/CD tool that makes your life (and the planet) better!"]]]])

(defn footer []
  [:div.footer.border-top.mt-3
   [:p "built by " [:a {:href "https://www.monkey-projects.be"} "Monkey Projects"]]])

(defn welcome
  "Renders welcome panel with the subpanel as a child"
  [subpanel]
  [:div
   [header]
   [:div.row
    [:div.col
     [co/logo]]
    [:div.col
     subpanel]]
   [footer]])

(defn default [subpanel]
  [:div
   [header]
   subpanel
   [footer]])
