(ns monkey.ci.gui.test.cards.modal-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.modals :as sut]
            [monkey.ci.gui.utils :as u]
            [reagent.core]))

(defcard-rg simple-modal
  "Simple modal dialog"
  (let [id :test-modal]
    [:div
     [sut/modal id
      [:h5 "Test Dialog"]
      [:p "This is the dialog contents"]]
     [:button.btn.btn-primary {:type :button
                               :data-bs-toggle "modal"
                               :data-bs-target (u/->dom-id id)}
      "Show Dialog"]]))
