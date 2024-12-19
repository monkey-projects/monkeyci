(ns monkey.ci.gui.admin
  (:require [monkey.ci.gui.core :as c]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]))

(defn render-admin []
  [l/default
   [:<>
    [:h1 "Admin Site"]
    [:p "Welcome, please be careful."]]])

(defn ^:dev/after-load reload []
  (c/reload [render-admin]))

(defn init []
  (r/start-admin!)
  (reload))
