(ns monkey.ci.gui.test.cards.repo-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.repo.views :as sut]
            [reagent.core]
            [re-frame.db :as rdb]))

(defcard-rg labels
  "Simple labels component"
  [sut/labels [{:name "project"
                :value "MonkeyCI"}
               {:name "kind"
                :value "Application"}]])
