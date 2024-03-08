(ns monkey.ci.gui.event-stream.views
  (:require [monkey.ci.gui.server-events :as se]
            [re-frame.core :as rf]))

(def stream-id "test-stream")

(rf/reg-event-db
 ::handle-event
 (fn [db [_ evt]]
   (update db ::events conj evt)))

(rf/reg-sub
 ::events
 (fn [db _]
   (::events db)))

(rf/reg-event-db
 ::clear-events
 (fn [db _]
   (assoc db ::events [])))

(defn th [& labels]
  (->> labels
       (map (partial vector :th))
       (into [:tr])))

(defn td [fields]
  (->> fields
       (map (partial vector :td))
       (into [:tr])))

(defn- event-row [evt]
  (let [f (juxt :time :type :message)]
    (td (f evt))))

(defn page []
  (let [events (rf/subscribe [::events])]
    [:<>
     [:h1 "Event Stream"]
     [:p "Event stream overview goes here"]
     [:button.btn.btn-primary.me-2
      {:on-click #(rf/dispatch [:event-stream/start stream-id [::handle-event]])}
      "Start Stream"]
     [:button.btn.btn-secondary.me-2
      {:on-click #(rf/dispatch [:event-stream/stop stream-id])}
      "Stop Stream"]
     [:button.btn.btn-danger
      {:on-click #(rf/dispatch [::clear-events])}
      "Clear"]     
     [:table.table.table-striped
      [:thead (th "Time" "Event" "Message")]
      (into [:tbody] (map event-row @events))]]))
