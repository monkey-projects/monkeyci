(ns monkey.ci.gui.home.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.home.events]
            [monkey.ci.gui.home.subs]
            [re-frame.core :as rf]))

(defn- cust-item [{:keys [id name]}]
  [:li [:a {:href (r/path-for :page/customer {:customer-id id})} name]])

(defn customers []
  (let [c (rf/subscribe [:user/customers])]
    (when @c
      [:<>
       [:h3 "Your Customers"]
       (->> (map cust-item @c)
            (into [:ul]))])))

(defn user-home [u]
  (rf/dispatch [:user/load-customers])
  [:<>
   [co/alerts [:user/alerts]]
   [customers]])

(defn redirect-to-login []
  [:p "One moment, redirecting you to the login page"]
  (rf/dispatch [:route/goto :page/login]))

(defn page
  "Renders user home page, or redirects to login if user has not authenticated yet."
  []
  ;; TODO When a valid token was stored, use it to fetch user info
  (let [u (rf/subscribe [:login/user])]
    [l/default
     [:<>
      (if @u
        [user-home @u]
        [redirect-to-login])]]))
