(ns monkey.ci.gui.test.cards.chart-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.logging :as log]
            [reagent.core :as rc]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]
            ;; TODO Finetune loaded modules
            ["chart.js/auto" :refer [Chart]]
            #_["chart.js" :refer [BarController BarElement CategoryScale LinearScale
                                  Chart]]))

(defn- set-chart-config [db id conf]
  (assoc-in db [::chart-config id] conf))

(defn- chart-config [db id]
  (get-in db [::chart-config id]))

(defn- configure-chart! [id chart config]
  ;; FIXME No matter what, the chart always re-renders, so the change check doesn't work.
  (when (or (nil? chart)
            ;; Only update when the chart data has changed
            (not= (js->clj (.-data chart)) (:data config)))
    (when chart
      (.destroy chart))
    (if-let [el (.getElementById js/document (name id))]
      (Chart. el (clj->js config))
      (log/warn "Element" (name id) "not found in document"))))

(rf/reg-cofx
 :chart/configurator
 (fn [cofx _]
   (assoc cofx :chart/configurator configure-chart!)))

#_(rf/reg-fx
 :chart/configure
 (fn [[id config]]
   (log/debug "Configuring chart:" (name id))
   (if-let [el (.getElementById js/document (name id))]
     ;; We need to directly manipulate the app db because the chart
     ;; is directly modifying the DOM.
     (set-chart-config @rdb/app-db id (Chart. el (clj->js config)))
     (log/warn "Element" (name id) "not found in document"))))

#_(rf/reg-fx
 :chart/set-data
 (fn [[chart data]]
   (when chart
     (log/debug "Updating chart data")
     (set! (.-data chart) data))))

#_(rf/reg-fx
 :chart/destroy
 (fn [chart]
   (when chart
     (.destroy chart))))

(rf/reg-event-fx
 :chart/update
 [(rf/inject-cofx :chart/configurator)]
 (fn [{:keys [db :chart/configurator]} [_ chart-id new-config]]
   (let [old-config (chart-config db chart-id)]
     {:db (set-chart-config db chart-id (configurator chart-id old-config new-config))})))

#_(rf/reg-sub
 :chart/config
 (fn [db [_ id]]
   (chart-config db id)))

(defn chart-component [id]
  [:canvas {:id id}]
  #_(rc/create-class
     {:reagent-render
      (fn [id config]
        ;; Render the component, chart js will fill it up after mount
        [:canvas {:id id}])
      :component-did-mount
      (fn [this]
        #_(Chart. (.getElementById js/document id) (clj->js config))
        #_(rf/dispatch [:chart/update id config]))}))

(defcard-rg bar-chart
  "Simple bar chart"
  (let [id "bar-chart"
        data {:labels (range 10)
              :datasets [{:label "Values"
                          :data [5 3 6 7 3 2 5 6 8 7]
                          :borderWidth 1}]}
        config {:type "bar"
                :data data}]
    (rf/dispatch [:chart/update id config])
    [l/error-boundary
     [:div
      [:h2 "Bar chart"]
      [chart-component id]]]))

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
      [chart-component id]]]))

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
      [chart-component id]]]))
