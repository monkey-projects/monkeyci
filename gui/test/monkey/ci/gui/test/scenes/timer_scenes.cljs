(ns monkey.ci.gui.test.scenes.timer-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.timer :as sut]
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
