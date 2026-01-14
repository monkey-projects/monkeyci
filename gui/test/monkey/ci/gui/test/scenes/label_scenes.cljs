(ns monkey.ci.gui.test.scenes.label-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.labels :as sut]))

(defscene label-filter-render
  "Display editor for label filters"
  [sut/render-filter-editor
   ::filter-editor-0
   [[{:label "label-1" :value "value 1"}
     {:label "label-2" :value "value 2"}]
    [{:label "label-1" :value "value 4"}
     {:label "label-3" :value "value 3"}]
    [{:label "label-3" :value "value 5"}]]])

(defscene label-filter-editor
  "Functional label filter editor"
  [sut/edit-label-filters ::filter-editor-1])
