(ns monkey.ci.gui.home.views
  (:require [monkey.ci.gui.layout :as l]
            [re-frame.core :as rf]))

(defn user-home [u]
  (println "Current user:" u)
  [:div
   [:h3 "Welcome, " (:name u)]
   (when-let [a (:avatarUrl u)]
     [:img.img-thumbnail {:width "100px" :src a :alt "Avatar"}])])

(defn redirect-to-login []
  [:p "One moment, redirecting you to the login page"]
  (rf/dispatch [:route/goto :page/login]))

(defn page
  "Renders user home page, or redirects to login if user has not authenticated yet."
  []
  (let [u (rf/subscribe [:login/user])]
    [l/default
     [:<>
      (if @u
        [user-home @u]
        [redirect-to-login])]]))
