(ns monkey.ci.gui.test.scenes.chart-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.charts :as sut]
            [monkey.ci.gui.layout :as l]
            [reagent.core :as rc]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(defscene bar-chart
  "Simple bar chart"
  (let [id "bar-chart"
        data {:labels (range 10)
              :datasets [{:label "Values"
                          :data [5 3 6 7 3 2 5 6 8 7]
                          :backgroundColor "#008060"}]}
        config {:type "bar"
                :data data}]
    [l/error-boundary
     [:div
      [:h2 "Bar chart"]
      [sut/chart-component id config]]]))

(defscene line-chart
  "Simple line chart"
  (let [id "line-chart"
        data {:labels (range 10)
              :datasets [{:label "Values"
                          :data [5 4 6 7 3 2 5 6 8 7]
                          :tension 0.2}]}
        config {:type "line"
                :data data}]
    [l/error-boundary
     [:div
      [:h2 "Line chart"]
      [sut/chart-component id config]]]))

(defscene donut-chart
  "Donut chart"
  (let [id "donut-chart"
        data {:labels ["Unit" "Integration" "End-to-end"]
              :datasets [{:label "Values"
                          :data [2 5 8]
                          :borderWidth 1}]}
        config {:type "doughnut"
                :data data}]
    [l/error-boundary
     [:div
      [:h2 "Test durations by suite"]
      [sut/chart-component id config]]]))
