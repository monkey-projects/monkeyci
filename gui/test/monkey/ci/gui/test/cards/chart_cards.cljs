(ns monkey.ci.gui.test.cards.chart-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.charts :as sut]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.logging :as log]
            [reagent.core :as rc]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]
            ;; TODO Finetune loaded modules
            ["chart.js/auto" :refer [Chart]]
            #_["chart.js" :refer [BarController BarElement CategoryScale LinearScale
                                  Chart]]))

(defn chart-component-2 [id config]
  ;; The main issue with the chart component is that it requires an existing
  ;; DOM object before it can render the chart.  So we can't really use the
  ;; regular system of "prepare the data and set it in the hiccup structure".
  (letfn [(make-chart [conf]
            (Chart. (.getElementById js/document id) (clj->js conf)))
          (update-chart [chart conf]
            ;; Replace the data in the chart, leave other values untouched
            (log/debug "Updating chart data")
            (sut/update-chart-data! chart conf)
            (.update chart #_"none")
            chart)]
    (let [state (rc/atom nil)]
      (rc/create-class
       {:display-name "chart-component"
        :reagent-render
        (fn [id config]
          ;; Render the component, chart js will fill it up after mount
          (log/debug "Rendering component")
          @config ; Dereference the config so reagent will re-render on changes
          [:canvas {:id id}])
        :component-did-update
        (fn [this argv]
          (let [conf @(nth argv 2)]
            (log/debug "Component updated:" (str conf))
            (swap! state update-chart conf)))
        :component-did-mount
        (fn [this]
          (log/debug "Component mounted")
          (reset! state (make-chart @config)))}))))

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

(defcard-rg bar-chart-2
  "Simple bar chart with react class"
  (let [id "bar-chart-2"
        create-config (fn []
                        {:type "bar"
                         :data {:labels (range 10)
                                :datasets [{:label "Values"
                                            :data (repeatedly 10 (comp inc #(rand-int 10)))}]}})
        config (rc/atom (create-config))
        reset-config (fn []
                       (println "Resetting config")
                       (reset! config (create-config)))]
    [l/error-boundary
     [:div
      [chart-component-2 id config]
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
