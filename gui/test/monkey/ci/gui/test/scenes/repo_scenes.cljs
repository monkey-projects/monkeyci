(ns monkey.ci.gui.test.scenes.repo-scenes
  (:require [portfolio.reagent-18 :refer-macros [defscene]]
            [monkey.ci.gui.repo.views :as sut]
            [monkey.ci.gui.utils :as u]))

(defscene labels
  "Simple labels component"
  [sut/labels [{:name "project"
                :value "MonkeyCI"}
               {:name "kind"
                :value "Application"}]])

(defscene confirm-delete-modal
  "Delete confirmation dialog"
  [:div
   [sut/confirm-delete-modal
    {:name "test repo"}]
   [:button.btn.btn-primary
    {:data-bs-toggle :modal
     :data-bs-target (u/->dom-id ::sut/delete-repo-confirm)}
    "Show Dialog"]])
