(ns monkey.ci.gui.pages
  "Links route names to actual components to be rendered"
  (:require [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.login.views :as login]
            [monkey.ci.gui.login.subs]
            [monkey.ci.gui.build.views :as build]
            [monkey.ci.gui.customer.views :as customer]
            [monkey.ci.gui.repo.views :as repo]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(defn user-home [u]
  [:h3 "Welcome, " (:name u)])

(defn redirect-to-login []
  [:p "One moment, redirecting you to the login page"]
  (rf/dispatch [:route/goto (r/path-for :page/login)]))

(defn home []
  (let [u (rf/subscribe [:login/user])]
    [l/default
     [:<>
      (if @u
        [user-home u]
        [redirect-to-login])]]))

(def pages
  {:page/root home
   :page/build build/page
   :page/login login/page
   :page/github-callback login/github-callback
   :page/customer customer/page
   :page/repo repo/page})

(defn render-page [route]
  (println "Rendering page for route:" (get-in route [:data :name]))
  (if-let [p (get pages (get-in route [:data :name]))]
    [p route]
    [:div.alert.alert-warning
     [:h3 "Page not found"]
     [:p "No page exists for route " [:b (str (get-in route [:data :name]))]]]))

(defn render []
  (let [r (rf/subscribe [:route/current])]
    [render-page @r]))
