(ns monkey.ci.gui.admin.mailing.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.utils :as u]
            [monkey.ci.gui.admin.mailing.events :as e]
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
     [:a.btn.btn-primary.align-self-start
      {:href (r/path-for :admin/new-email)}
      [co/icon-text :plus-square "New Mailing"]]]
    [:p "Create new mailings, or review past mailings."]
    [overview-table]]])

(defn- new-mailing-form []
  [:form
   [:div.mb-3
    [:label.form-label {:for :subject} "Subject"]
    [:input.form-control
     {:name :subject
      :id :subject}]]
   [:div.mb-3
    [:label.form-label {:for :html-body} "Html Content"]
    [:textarea.form-control
     {:name :html-body
      :id :html-body
      :rows 10}]]
   [:div.mb-3
    [:label.form-label {:for :text-body} "Text Content"]
    [:textarea.form-control
     {:name :text-body
      :id :text-body
      :rows 10}]]
   [:div.d-flex.gap-2
    [:button.btn.btn-primary
     {:on-click (u/link-evt-handler [::e/save-mailing])}
     [co/icon-text :save "Save"]]
    [co/cancel-btn [::e/cancel-edit]]]])

(defn new-mailing [_]
  [l/default
   [:div.card
    [:div.card-body
     [:h3.card-title "New Mailing"]
     [new-mailing-form]]]])
