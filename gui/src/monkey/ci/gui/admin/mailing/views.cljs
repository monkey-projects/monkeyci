(ns monkey.ci.gui.admin.mailing.views
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.time :as time]
            [monkey.ci.gui.utils :as u]
            [monkey.ci.gui.admin.mailing.events :as e]
            [monkey.ci.gui.admin.mailing.subs :as s]
            [re-frame.core :as rf]))

(defn- mailing-actions [row]
  [:div.d-flex.gap-2.justify-content-end
   [:a.btn.btn-sm.btn-outline-primary
    {:href (r/path-for :admin/mailing-edit {:mailing-id (:id row)})
     :title "Edit mailing"}
    [co/icon :pencil-square]]])

(defn overview-table []
  [t/paged-table
   {:id ::overview
    :items-sub [::s/mailing-list]
    :columns (-> [{:label "Time"
                   :value (comp time/format-datetime time/parse-epoch :creation-time)}
                  {:label "Subject"
                   :value :subject}
                  {:value mailing-actions}]
                 (t/add-sorting 0 :desc))
    :loading {:sub [::s/loading?]}}])

(defn overview [_]
  (rf/dispatch [::e/load-mailings])
  (fn [_]
    [l/default
     [:<>
      [:div.d-flex
       [:div.flex-grow-1
        [:h3 "Mailing"]]
       [:a.btn.btn-primary.align-self-start
        {:href (r/path-for :admin/new-mailing)}
        [co/icon-text :plus-square "New Mailing"]]]
      [:p "Create new mailings, or review past mailings."]
      [a/component [::s/alerts]]
      [:div.card
       [:div.card-body
        [overview-table]]]]]))

(defn- edit-mailing-form []
  (let [e (rf/subscribe [::s/editing])]
    [:form
     [:div.mb-3
      [:label.form-label {:for :subject} "Subject"]
      [:input.form-control
       {:name :subject
        :id :subject
        :value (:subject @e)
        :on-change (u/form-evt-handler [::e/edit-prop-changed :subject])}]]
     [:div.mb-3
      [:label.form-label {:for :html-body} "Html Content"]
      [:textarea.form-control
       {:name :html-body
        :id :html-body
        :rows 10
        :value (:html-body @e)
        :on-change (u/form-evt-handler [::e/edit-prop-changed :html-body])}]]
     [:div.mb-3
      [:label.form-label {:for :text-body} "Text Content"]
      [:textarea.form-control
       {:name :text-body
        :id :text-body
        :rows 10
        :value (:text-body @e)
        :on-change (u/form-evt-handler [::e/edit-prop-changed :text-body])}]]
     [:div.d-flex.gap-2
      [:button.btn.btn-primary
       {:on-click (u/link-evt-handler [::e/save-mailing])}
       [co/icon-text :save "Save"]]
      [co/cancel-btn [::e/cancel-edit]]]]))

(defn new-mailing [_]
  [l/default
   [:div.card
    [:div.card-body
     [:h3.card-title [co/icon-text :envelope-plus "New Mailing"]]
     [:p "Create a new mailing.  It will only be sent after you configure the destinations."]
     [a/component [::s/edit-alerts]]
     [edit-mailing-form]]]])

(defn edit-mailing [route]
  (let [id (-> route (r/path-params) :mailing-id)]
    (rf/dispatch [::e/load-mailing id])
    [l/default
     [:div.card
      [:div.card-body
       [:h3.card-title [co/icon-text :envelope-plus "Edit Mailing"]]
       [:p "Edit mailing settings, or send it out."]
       [a/component [::s/edit-alerts]]
       [edit-mailing-form]]]]))
