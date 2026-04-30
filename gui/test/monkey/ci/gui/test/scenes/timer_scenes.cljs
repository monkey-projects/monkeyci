(ns monkey.ci.gui.test.scenes.timer-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.timer :as sut]
            [monkey.ci.gui.time :as t]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::simple-timer
 (fn [db [_ ticks]]
   (assoc db ::ticks ticks)))

(rf/reg-sub
 ::simple-ticks
 (fn [db _]
   (::ticks db)))

(defscene simple-timer
  "Simple timer"
  (fn []
    (let [t (rf/subscribe [::simple-ticks])]
      [:<>
       [sut/timer ::simple 1000 [::simple-timer]]
       [:p "Ticks: " @t]])))

(defscene interval-timer
  (fn []
    [sut/interval-timer
     1000
     (fn []
       [:p (str "Current time: " (t/now))])]))
