(ns monkey.ci.gui.timer
  "Timer component"
  (:require [monkey.ci.gui.time :as t]
            [reagent.core :as rc]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::timer-tick
 (fn [{:keys [db]} [_ id evt]]
   {:db (update-in db [::timer id] (fnil inc 0))
    :dispatch evt}))

(rf/reg-sub
 ::timer-ticks
 (fn [db [_ id]]
   (get-in db [::timer id])))

(defn timer
  "Renders a timer component that dispatches the given event after the timeout.
   As long as the component remains active, the timer is also active."
  [id timeout evt]
  (fn [_ _ _]
    (let [ticks (rf/subscribe [::timer-ticks id])]
      ;; We could also use an fx handler for this
      #?(:cljs
         (js/setTimeout #(rf/dispatch [::timer-tick id (conj evt @ticks)]) timeout))
      ;; We need this otherwise component won't be rendered and timer not activated
      [:div {:style {:display :none}} @ticks])))

(defn interval-timer
  "A reagent component that periodically invokes the given renderer function
   with the current time as argument.  When the component is unmounted, the 
   timer is stopped."
  [ms renderer]
  (let [now (rc/atom nil)]
    (fn [_ _]
      (js/setTimeout #(reset! now (t/now)) ms)
      (renderer @now))))
