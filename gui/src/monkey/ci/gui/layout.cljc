(ns monkey.ci.gui.layout
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn user-info []
  (let [u (rf/subscribe [:login/user])]
    (when @u
      [:div
       [co/user-avatar @u]
       [:p (:name @u) 
        " | "
        [:a {:href "" :on-click (u/link-evt-handler [:login/sign-off])}
         "sign off"]]])))

(defn header []
  [:div.header
   [:div.row
    [:div.col-1
     [:div.mt-2 [:a {:href "/"} [co/logo]]]]
    [:div.col-9
     [:h1 "MonkeyCI"]
     [:p.lead "Welcome to MonkeyCI, the CI/CD tool that makes your life (and the planet) better!"]]
    [:div.col-2.text-end
     [user-info]]]])

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
