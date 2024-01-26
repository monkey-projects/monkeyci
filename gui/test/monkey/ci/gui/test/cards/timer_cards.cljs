(ns monkey.ci.gui.test.cards.timer-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.timer :as sut]
            [reagent.core]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(rf/reg-event-db
 ::simple-timer
 (fn [db [_ ticks]]
   (assoc db ::ticks ticks)))

(rf/reg-sub
 ::simple-ticks
 (fn [db _]
   (::ticks db)))

(defcard-rg simple-timer
  "Simple timer"
  (fn []
    (let [t (rf/subscribe [::simple-ticks])]
      [:<>
       [sut/timer ::simple 1000 [::simple-timer]]
       [:p "Ticks: " @t]])))
