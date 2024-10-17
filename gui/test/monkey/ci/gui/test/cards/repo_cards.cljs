(ns monkey.ci.gui.test.cards.repo-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.repo.views :as sut]
            [monkey.ci.gui.utils :as u]
            [reagent.core]
            [re-frame.db :as rdb]))

(defcard-rg labels
  "Simple labels component"
  [sut/labels [{:name "project"
                :value "MonkeyCI"}
               {:name "kind"
                :value "Application"}]])

(defcard-rg confirm-delete-modal
  "Delete confirmation dialog"
  [:div
   [sut/confirm-delete-modal
    {:name "test repo"}]
   [:button.btn.btn-primary
    {:data-bs-toggle :modal
     :data-bs-target (u/->dom-id ::sut/delete-repo-confirm)}
    "Show Dialog"]])
