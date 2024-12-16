(ns monkey.ci.gui.test.cards.alert-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.alerts :as sut]
            [monkey.ci.gui.components :as c]
            [reagent.core]
            [re-frame.db :as rdb]))

(defcard-rg github-error
  "Github error alert"
  [c/render-alert
   (sut/cust-github-repos-failed
    "no permission")])
