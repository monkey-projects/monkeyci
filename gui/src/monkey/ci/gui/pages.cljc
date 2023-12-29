(ns monkey.ci.gui.pages
  "Links route names to actual components to be rendered"
  (:require [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.login.views :as login]
            [monkey.ci.gui.build.views :as build]
            [monkey.ci.gui.customer.views :as customer]
            [monkey.ci.gui.repo.views :as repo]
            [re-frame.core :as rf]))

(defn redirect []
  [l/default
   [:h3 "This is the root page, you should be redirected"]])

(def pages
  {:page/root redirect
   :page/build build/page
   :page/login login/page
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
