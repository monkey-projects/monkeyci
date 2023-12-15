(ns monkey.ci.gui.core
  (:require [reagent.core :as rc]
            [reagent.dom.client :as rd]))

(defn main []
  [:div
   [:h1 "It works!"]])

(defn init []
  (let [root (rd/create-root (.getElementById js/document "root"))]
    (rd/render root [main])))
