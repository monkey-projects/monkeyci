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

(defn update-chart-data!
  "In order to ensure smooth animation transition when changing chart data, we can't
   just replace the entire data structure, we need to replace all modified data values
   instead.  This function takes a new dataset and applies the differences to the old
   (js) datastructure."
  [old new]
  (letfn [(update-arr [old new]
            (println "Comparing:" old "and" (str new))
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
    (let [old-ds (-> old (.-data) (.-datasets))
          new-ds (get-in new [:data :datasets])]
      (doseq [i (range (count old-ds))]
        (let [upd (get new-ds i)]
          (update-arr (.-data (aget old-ds i))
                      (:data upd))))
      (update-arr (-> old (.-data) (.-labels)) (vec (get-in new [:data :labels])))
      old)))
