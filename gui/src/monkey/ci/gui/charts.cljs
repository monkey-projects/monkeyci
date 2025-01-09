(ns monkey.ci.gui.charts
  "Chart.js wrapper component"
  (:require [monkey.ci.gui.logging :as log]
            [oops.core :as oc]
            [re-frame.core :as rf]
            [reagent.core :as rc]
            #_["chart.js/auto"]
            ["chart.js" :refer [BarController BarElement
                                DoughnutController ArcElement
                                LineController PointElement LineElement
                                CategoryScale LinearScale
                                Colors Legend
                                Chart]]))

;; Necessary otherwise chartjs won't be able to use these
(.register Chart (clj->js [BarController BarElement
                           DoughnutController ArcElement
                           LineController PointElement LineElement
                           CategoryScale LinearScale
                           Colors Legend]))

(defn- set-chart-config [db id conf]
  (assoc-in db [::chart-config id] conf))

(defn- chart-config [db id]
  (get-in db [::chart-config id]))

(defn- configure-chart! [id chart config]
  ;; FIXME No matter what, the chart always re-renders, so the change check doesn't work.
  ;; TODO Find a way to change existing chart data instead of recreating it.
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

(defn ^Object get-data [chart]
  (oc/oget chart "data"))

(defn update-chart-data!
  "In order to ensure smooth animation transition when changing chart data, we can't
   just replace the entire data structure, we need to replace all modified data values
   instead.  This function takes a new dataset and applies the differences to the old
   (js) datastructure."
  [old new]
  (letfn [(update-arr [old new]
            (when (and old new)
              (let [new (vec new)
                    oc (count old)
                    nc (count new)]
                ;; Update values
                (doseq [i (range (min oc nc))]
                  (let [v (get new i)]
                    (when (not= (aget old i) v)
                      (aset old i v))))
                ;; Remove values
                (when (> oc nc)
                  (.splice old nc (- oc nc)))
                ;; Add values
                (while (< (count old) nc)
                  (.push old (get new (count old)))))))]
    (let [old-ds (oc/oget old "data.datasets")
          new-ds (get-in new [:data :datasets])]
      (doseq [i (range (count old-ds))]
        (let [upd (get new-ds i)]
          (update-arr (get-data (aget old-ds i))
                      (:data upd))))
      (update-arr (oc/oget old "data.?labels") (vec (get-in new [:data :labels])))
      old)))

(defn chart-component-2 [id config]
  ;; The main issue with the chart component is that it requires an existing
  ;; DOM object before it can render the chart.  So we can't really use the
  ;; regular system of "prepare the data and set it in the hiccup structure".
  (letfn [(make-chart [conf]
            ;;(log/debug "Creating new chart with config:" (str conf))
            (Chart. (.getElementById js/document (str id)) (clj->js conf)))
          (update-chart [chart conf]
            ;; Replace the data in the chart, leave other values untouched
            ;;(log/debug "Updating chart data")
            (update-chart-data! chart conf)
            (.update chart)
            chart)]
    (let [state (rc/atom nil)]
      (rc/create-class
       {:display-name "chart-component"
        :reagent-render
        (fn [id config]
          ;;(log/debug "Rendering component with config" (str config))
          ;; Put the config in the state, we'll need it after mount
          (swap! state assoc :config config)
          ;; Render the component, chart js will fill it up after mount
          [:canvas {:id (str id)}])
        :component-did-update
        (fn [this argv]
          ;; Don't use argv, it contains the original values
          ;;(log/debug "Component updated")
          ;; Update the chart configuration
          (swap! state (fn [{:keys [config] :as r}]
                         (update r :chart update-chart config))))
        :component-did-mount
        (fn [this]
          ;;(log/debug "Component mounted")
          ;; Create the chart object using the config stored when rendering
          (swap! state (fn [{:keys [config] :as r}]
                         (assoc r :chart (make-chart config)))))}))))
