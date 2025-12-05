(ns monkey.ci.gui.user.views
  "User preferences views"
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.tabs :as tabs]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [monkey.ci.gui.user.events :as e]
            [monkey.ci.gui.user.subs :as s]
            [re-frame.core :as rf]))

(defn- form-row [lbl input]
  [:div.row
   [:div.col-1.col-md-2 lbl]
   [:div.col-11.col-md-10 input]])

(defn- form-control [id lbl val & [input-opts]]
  (form-row
   [:label.form-label.mt-2 {:for id} lbl]
   [:input.form-control
    (merge {:name id
            :id id
            :value val
            :on-change (u/form-evt-handler [::e/general-update id])}
           input-opts)]))

(defn general-page [uid]
  (rf/dispatch [::e/general-load uid])
  (fn [uid]
    (let [s (rf/subscribe [::s/general-edit])
          saving? (rf/subscribe [::s/general-saving?])]
      [:div.card.flex-fill
       [:div.card-body
        [:div.card-title
         [co/page-title [:span.me-2 [co/icon :person-fill-gear]] "General Settings"]]
        [co/alerts [::s/general-alerts]]
        [:form.d-flex.flex-column.gap-2.mt-3
         [form-control :name "User" (str (name (:type @s)) " - " (:type-id @s))
          {:read-only true}]
         [form-control :email "E-mail" (:email @s)]
         [:div.row
          [:div.offset-1.offset-md-2.col-11.col-md-10
           [:div.form-check
            [:input.form-check-input
             {:type :checkbox
              :id :receive-mailing
              :checked (:receive-mailing @s)
              :on-change (u/form-evt-handler [::e/general-update :receive-mailing]
                                             u/evt->checked)}]
            [:label.form-check-label {:for :receive-mailing} "Receive mailings"]]]]
         [:div.row.mt-3
          [:div.offset-1.offset-md-2.col-11.col-md-10.d-flex.gap-2
           [:button.btn.btn-primary
            (cond-> {:on-click (u/link-evt-handler [::e/general-save])}
              @saving? (assoc :disabled true))
            [co/icon-text :floppy-fill "Save"]]
           [co/cancel-btn [::e/general-cancel]]]]]]])))

(defn tabs [id]
  [tabs/settings-tabs
   [{:id :general
     :header "General"
     :link :page/user}
    #_{:id :tokens
       :header "API Tokens"
       :link "todo"}]
   id])

(defn overview [r]
  (l/default
   [:<>
    [:div.d-flex.gap-2
     [tabs :general]
     [general-page (:user-id (r/path-params r))]]]))
