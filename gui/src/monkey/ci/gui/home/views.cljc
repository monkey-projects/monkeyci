(ns monkey.ci.gui.home.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(defn- cust-item [id]
  [:li [:a {:href (r/path-for :page/customer {:customer-id id})} id]])

(defn user-home [u]
  [:<>
   [:div.clearfix
    [:h3.float-start "Customers for " (:name u)]
    [co/user-avatar u]]
   (->> (:customers u)
        (map cust-item)
        (into [:ul]))])

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
