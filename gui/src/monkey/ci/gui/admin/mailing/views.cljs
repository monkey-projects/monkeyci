(ns monkey.ci.gui.admin.mailing.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.admin.mailing.subs :as s]
            [re-frame.core :as rf]))

(defn overview-table []
  ;; TODO
  [t/paged-table
   {:id ::overview
    :items-sub [::s/mailing-list]
    :columns [{:label "Time"
               :value :creation-time}
              {:label "Subject"
               :value :subject}]}])

(defn overview [_]
  [l/default
   [:<>
    [:div.d-flex
     [:div.flex-grow-1
      [:h3 "Mailing"]]
     [:button.btn.btn-primary.align-self-start
      [co/icon-text :plus-square "New Mailing"]]]
    [:p "Create new mailings, or review past mailings."]
    [overview-table]]])
