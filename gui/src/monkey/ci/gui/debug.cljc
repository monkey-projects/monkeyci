(ns monkey.ci.gui.debug
  "Debugging utility functions or components"
  (:require [monkey.ci.gui.routing]
            [re-frame.core :as rf]))

(defn curr-route
  "Displays current route settings"
  []
  (let [r (rf/subscribe [:route/current])]
    [:div.alert.alert-info
     [:div "Current route: " [:b (str (some-> @r :data :name))]]]))
