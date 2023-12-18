(ns monkey.ci.gui.pages
  "Links route names to actual components to be rendered"
  (:require [monkey.ci.gui.login.views :as login]
            [re-frame.core :as rf]))

(defn redirect []
  [:h3 "This is the root page, you should be redirected"])

(def pages
  {:page/root redirect
   :page/login login/page})

(defn render-page [route]
  (if-let [p (get pages (get-in route [:data :name]))]
    [p]
    [:div.alert.alert-warning
     [:h3 "Page not found"]
     [:p "No page exists for route " [:b (str (get-in route [:data :name]))]]]))

(defn render []
  (let [r (rf/subscribe [:route/current])]
    [render-page @r]))
