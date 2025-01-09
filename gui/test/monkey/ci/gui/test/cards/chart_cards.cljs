(ns monkey.ci.gui.test.cards.chart-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.charts :as sut]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.logging :as log]
            [reagent.core :as rc]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(defcard-rg bar-chart
  "Simple bar chart"
  (let [id "bar-chart"
        data {:labels (range 10)
              :datasets [{:label "Values"
                          :data [5 3 6 7 3 2 5 6 8 7]
                          :backgroundColor "#008060"}]}
        config {:type "bar"
                :data data}]
    (rf/dispatch [:chart/update id config])
    [l/error-boundary
     [:div
      [:h2 "Bar chart"]
      [sut/chart-component id]]]))

(rf/reg-event-db
 ::bar-chart-2--update
 (fn [db [_ config]]
   (println "Updating chart configuration in re-frame db")
   (assoc db ::bar-chart-2 config)))

(rf/reg-sub
 ::bar-chart-2
 (fn [db _]
   (::bar-chart-2 db)))

(defn bar-chart-2 [id]
  (let [conf (rf/subscribe [::bar-chart-2])]
    [sut/chart-component-2 id @conf]))

(defcard-rg bar-chart-2
  "Simple bar chart with react class"
  (let [id :bar-chart-2
        create-config (fn []
                        {:type "bar"
                         :data {:labels (range 10)
                                :datasets [{:label "Values"
                                            :data (repeatedly 10 (comp inc #(rand-int 10)))}]}})
        reset-config (fn []
                       (println "Resetting config")
                       #_(reset! config (create-config))
                       (rf/dispatch [::bar-chart-2--update (create-config)]))]
    (rf/dispatch-sync [::bar-chart-2--update (create-config)])
    [l/error-boundary
     [:div
      [bar-chart-2 id]
      [:button.btn.btn-primary {:on-click #(reset-config)} "Reset"]]]))

(defcard-rg line-chart
  "Simple line chart"
  (let [id "line-chart"
        data {:labels (range 10)
              :datasets [{:label "Values"
                          :data [5 4 6 7 3 2 5 6 8 7]
                          :tension 0.2}]}
        config {:type "line"
                :data data}]
    (rf/dispatch [:chart/update id config])
    [l/error-boundary
     [:div
      [:h2 "Line chart"]
      [sut/chart-component id]]]))

(defcard-rg donut-chart
  "Donut chart"
  (let [id "donut-chart"
        data {:labels ["Unit" "Integration" "End-to-end"]
              :datasets [{:label "Values"
                          :data [2 5 8]
                          :borderWidth 1}]}
        config {:type "doughnut"
                :data data}]
    (rf/dispatch [:chart/update id config])
    [l/error-boundary
     [:div
      [:h2 "Test durations by suite"]
      [sut/chart-component id]]]))
