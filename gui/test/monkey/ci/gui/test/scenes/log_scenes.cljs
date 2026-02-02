(ns monkey.ci.gui.test.scenes.log-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.components :as sut]))

(defscene plain-log
  "Log contents without coloring"
  [sut/log-contents
   (->> (range 10)
        (mapv (fn [idx]
               (str "This is line " (inc idx))))
        (interpose [:br])
        vector)])

(defscene colored-log
  "Log with ansi coloring"
  [sut/log-contents ["This is \033[32mcolored\033[0;39m."]])

(defscene long-line-wrap
  "Log with very long line and wrapping enabled"
  [sut/log-contents (repeat 30 "This is a very long line. ") {:wrap? true}])
