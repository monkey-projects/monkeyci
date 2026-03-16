(ns monkey.ci.gui.test.scenes.dashboard-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.dashboard.views :as sut]))

(defscene live-log
  "Live log with various kinds of messages"
  [sut/live-log
   {:repo-id "monkeyci"
    :build-idx 1409}])

(defscene active-build-buttons
  [:div.flex.gap-2
   [sut/abort-btn]
   [sut/logs-btn]
   [sut/cancel-btn]])

(defscene total-runs-metrics
  [sut/total-runs-metrics
   {:value 1234
    :diff 2.45
    :progress 0.85
    :status :success}])
