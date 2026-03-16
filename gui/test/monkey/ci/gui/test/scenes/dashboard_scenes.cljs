(ns monkey.ci.gui.test.scenes.dashboard-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.dashboard.views :as sut]))

(defscene live-log
  "Live log with various kinds of messages"
  [sut/live-log])
