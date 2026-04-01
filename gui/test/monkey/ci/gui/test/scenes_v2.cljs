(ns monkey.ci.gui.test.scenes-v2
  "Portfolio scenes"
  (:require [portfolio.ui :as ui]))

(defn ^:export init []
  (ui/start!
   {:config
    {:css-paths ["/css/main.css"]}}))
