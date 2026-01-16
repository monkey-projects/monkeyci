(ns monkey.ci.gui.charts
  "Chart.js wrapper component"
  (:require [monkey.ci.gui.logging :as log]
            [oops.core :as oc]
            [reagent.core :as rc]
            ["chart.js" :refer [BarController BarElement
                                DoughnutController ArcElement
                                LineController PointElement LineElement
                                CategoryScale LinearScale
                                Colors Legend Tooltip
                                Chart]]
            ["react" :as react]))

;; Necessary otherwise chartjs won't be able to use these
(.register Chart (clj->js [BarController BarElement
                           DoughnutController ArcElement
                           LineController PointElement LineElement
                           CategoryScale LinearScale
                           Colors Legend Tooltip]))

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

(defn chart-component [id config]
  ;; The main issue with the chart component is that it requires an existing
  ;; DOM object before it can render the chart.  So we can't really use the
  ;; regular system of "prepare the data and set it in the hiccup structure".
  (let [state (rc/atom nil)
        ;; Component ref, will be populated with the dom element
        co-ref (react/createRef)]
    (letfn [(make-chart [conf]
              (if-let [el (.-current co-ref)]
                (Chart. el (clj->js conf))
                (log/warn "Chart element not found:" id)))
            (update-chart [chart conf]
              (doto chart
                ;; Replace the data in the chart, leave other values untouched
                (update-chart-data! conf)
                (.update)))]
      (rc/create-class
       {:display-name "chart-component"
        :reagent-render
        (fn [id config]
          ;; Put the config in the state, we'll need it after mount
          (swap! state assoc :config config)
          ;; Render the component, chart js will fill it up after mount.  Make sure
          ;; to populate the ref with the dom element.
          [:canvas {:id (str id) :ref co-ref}])
        :component-did-update
        (fn [this argv]
          ;; Don't use argv, it contains the original values
          ;; Update the chart configuration
          (swap! state (fn [{:keys [config] :as r}]
                         (update r :chart update-chart config))))
        :component-did-mount
        (fn [this]
          ;; Create the chart object using the config stored when rendering
          (swap! state (fn [{:keys [config] :as r}]
                         (assoc r :chart (make-chart config)))))}))))
