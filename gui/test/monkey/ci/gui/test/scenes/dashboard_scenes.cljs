(ns monkey.ci.gui.test.scenes.dashboard-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.dashboard.main.views :as sut]))

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

(defscene success-rate-metrics--good
  [sut/success-rate-metrics
   {:value 0.94
    :diff 2.1}])

(defscene success-rate-metrics--bad
  [sut/success-rate-metrics
   {:value 0.87
    :diff -2.1}])

(defscene avg-duration-metrics--good
  [sut/avg-duration-metrics
   {:value "3m24s"
    :diff 5.1}])

(defscene avg-duration-metrics--bad
  [sut/avg-duration-metrics
   {:value "5m16s"
    :diff -6.3}])

(defscene failures-metrics
  [sut/failures-metrics
   {:value 75
    :diff 6}])
