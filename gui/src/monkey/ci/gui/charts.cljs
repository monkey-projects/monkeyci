(ns monkey.ci.gui.charts
  "Chart.js wrapper component"
  (:require [monkey.ci.gui.logging :as log]
            [re-frame.core :as rf]
            ;; TODO Finetune loaded modules
            ["chart.js/auto" :refer [Chart]]))

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
    (log/debug "Configuring chart on element:" (name id))
    ;; In node there is no document
    (when (exists? js/document)
      (if-let [el (.getElementById js/document (name id))]
        (Chart. el (clj->js config))
        (log/warn "Element" (name id) "not found in document")))))

(rf/reg-cofx
 :chart/configurator
 (fn [cofx _]
   (assoc cofx :chart/configurator configure-chart!)))

(rf/reg-event-fx
 :chart/update
 [(rf/inject-cofx :chart/configurator)]
 (fn [{:keys [db :chart/configurator]} [_ chart-id new-config]]
   (let [old-config (chart-config db chart-id)]
     {:db (set-chart-config db chart-id (configurator chart-id old-config new-config))})))

(defn chart-component
  "Renders a canvas with the given id.  Use the `:chart/update` event to
   actually initialize and render the chart."
  [id]
  [:canvas {:id id}])
