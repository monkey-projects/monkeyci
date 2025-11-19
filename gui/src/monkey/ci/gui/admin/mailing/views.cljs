(ns monkey.ci.gui.admin.mailing.views
  (:require [clojure.string :as cs]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.tabs :as tabs]
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
    :loading {:sub [::s/loading?]}
    :on-row-click #(rf/dispatch [:route/goto :admin/mailing-edit {:mailing-id (:id %)}])}])

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
  (rf/dispatch [::e/new-mailing])
  (fn [_]
    [l/default
     [:div.card
      [:div.card-body
       [:h3.card-title [co/icon-text :envelope-plus "New Mailing"]]
       [:p "Create a new mailing.  It will only be sent after you configure the destinations."]
       [a/component [::s/edit-alerts]]
       [edit-mailing-form]]]]))

(defn- edit-mailing-general []
  [:<>
   [:p "Edit general properties."]
   [:div.card
    [:div.card-body
     [a/component [::s/edit-alerts]]
     [edit-mailing-form]]]])

(defn- check-icon [v]
  (if v
    [:span.text-success
     [co/icon :check-circle]]
    [:span.text-danger
     [co/icon :x-square]]))

(defn- sent-mailings-table []
  [t/paged-table
   {:id ::sent-mailings
    :items-sub [::s/sent-mailings]
    :columns (-> [{:label "Time"
                   :value (comp time/format-datetime time/parse-epoch :sent-at)}
                  {:label "To Users"
                   :value (comp check-icon :to-users)}
                  {:label "To Subscribers"
                   :value (comp check-icon :to-subscribers)}
                  {:label "Others"
                   :value (comp (partial cs/join ", ") :other-dests)}]
                 (t/add-sorting 0 :desc))
    :loading {:sub [::s/sent-loading?]}}])

(defn- delivery-form []
  (when @(rf/subscribe [::s/show-delivery?])
    (let [e (rf/subscribe [::s/new-delivery])]
      [:form
       [:h4 "Create New Delivery"]
       [:div.form-check.mb-2
        [:input.form-check-input
         {:type :checkbox
          :name :to-users
          :id :to-users
          :checked (:to-users @e)
          :on-change (u/form-evt-handler [::e/delivery-prop-changed :to-users]
                                         u/evt->checked)}]
        [:label.form-label {:for :to-users} "Send to users"]]
       [:div.form-check.mb-3
        [:input.form-check-input
         {:type :checkbox
          :name :to-subscribers
          :id :to-subscribers
          :checked (:to-subscribers @e)
          :on-change (u/form-evt-handler [::e/delivery-prop-changed :to-subscribers]
                                         u/evt->checked)}]
        [:label.form-label {:for :to-subscribers} "Send to subscribers"]]
       [:div.mb-3
        [:label.form-label {:for :text-body} "Other destinations"]
        [:textarea.form-control
         {:name :other-dests
          :id :other-dests
          :rows 5
          :value (:other-dests @e)
          :on-change (u/form-evt-handler [::e/delivery-prop-changed :other-dests])}]
        [:div.form-text
         "Additional email addresses the mailing will be sent to.  One per line."]]
       [:div.d-flex.gap-2
        [:button.btn.btn-primary
         {:on-click (u/link-evt-handler [::e/save-delivery])}
         [co/icon-text :mailbox-flag "Send"]]
        [co/cancel-btn [::e/cancel-delivery]]]])))

(defn- send-mailing []
  (rf/dispatch [::e/load-sent-mailings])
  (fn []
    (let [show-form? (rf/subscribe [::s/show-delivery?])]
      [:<>
       [:p "Send mailing to specific recipients or all users."]
       [a/component [::s/sent-alerts]]
       [:div.card
        [:div.card-body
         [sent-mailings-table]
         [:button.btn.btn-primary.mt-3
          (cond-> {:on-click (u/link-evt-handler [::e/new-delivery])}
            @show-form? (assoc :disabled true))
          [co/icon-text :envelope-paper "New Delivery"]]
         [:div.mt-4
          [delivery-form]]]]])))

(defn edit-mailing [route]
  (let [id (-> route (r/path-params) :mailing-id)]
    (rf/dispatch [::e/load-mailing id])
    [l/default
     [:<>
      [:h3.card-title [co/icon-text :envelope-plus "Edit Mailing"]]
      [:p "Edit mailing settings, or send it out."]
      [tabs/tabs ::edit
       [{:header "General"
         :contents [edit-mailing-general]}
        {:header "Send"
         :contents [send-mailing]}]]]]))
