(ns monkey.ci.gui.test.scenes.chart-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.charts :as sut]
            [monkey.ci.gui.layout :as l]
            [reagent.core :as rc]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(defn render-chart [{:keys [id title config]}]
  [l/error-boundary
   [:div
    [:h2 title]
    [sut/chart-component id config]]])

(defscene bar-chart
  :title "Simple bar chart"
  :params {:id "bar-chart"
           :title "Bar Chart"
           :config
           {:type :bar
            :data
            {:labels (range 10)
             :datasets [{:label "Values"
                         :data [5 3 6 7 3 2 5 6 8 7]
                         :backgroundColor "#008060"}]}}}
  render-chart)

(defscene line-chart
  :title "Simple line chart"
  :params {:id "line-chart"
           :title "Line Chart"
           :config
           {:type :line
            :data
            {:labels (range 10)
             :datasets [{:label "Values"
                         :data [5 4 6 7 3 2 5 6 8 7]
                         :tension 0.2}]}}}
  render-chart)

(defscene donut-chart
  :title "Donut chart"
  :params {:id "donut-chart"
           :config
           {:type :doughnut
            :data
            {:labels ["Unit" "Integration" "End-to-end"]
             :datasets [{:label "Values"
                         :data [2 5 8]
                         :borderWidth 1}]}}}
  render-chart)
