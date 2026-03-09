(ns monkey.ci.gui.test.scenes
  "Portfolio scenes"
  (:require [portfolio.ui :as ui]))

(defn ^:export init []
  (ui/start!
   {:config
    {:css-paths ["https://assets.staging.monkeyci.com/css/theme.min.css"
                 "https://assets.staging.monkeyci.com/css/vendor.min.css"]}}))
