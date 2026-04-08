(ns monkey.ci.gui.test.scenes.dagre-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.dagre :as sut]))

(defscene basic-network
  ;; Arrowheads not showing in scenes for unknown reason...
  [sut/dagre-network
   ::basic-network
   {:nodes ["unit-test" "deps-check" "publish"]
    :edges [{:from "publish" :to "unit-test"}
            {:from "publish" :to "deps-check"}]}
   {}])
