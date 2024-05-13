(ns monkey.ci.gui.test.cards.log-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [clojure.string :as cs]
            [monkey.ci.gui.components :as sut]
            [reagent.core]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(defcard-rg plain-log
  "Log contents without coloring"
  [sut/log-contents
   (->> (range 10)
        (mapv (fn [idx]
               (str "This is line " (inc idx))))
        (interpose [:br])
        vector)])

(defcard-rg colored-log
  "Log with ansi coloring"
  [sut/log-contents ["This is \033[32mcolored\033[0;39m."]])
