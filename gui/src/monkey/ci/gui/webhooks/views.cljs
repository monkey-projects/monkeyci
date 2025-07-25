(ns monkey.ci.gui.webhooks.views
  "Repository webhooks editing page"
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.repo-settings.views :as settings]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.webhooks.events]
            [monkey.ci.gui.webhooks.subs]
            [re-frame.core :as rf]))

(defn- webhook-actions [wh]
  [:button.btn.btn-danger.btn-icon.btn-sm co/delete-icon])

(defn webhooks-table []
  [t/paged-table
   {:id ::webhooks
    :items-sub [:repo/webhooks]
    :columns
    [{:label "Id"
      :value :id}
     {:label "Created at"
      :value :creation-time}
     {:label "Last invocation"
      :value :last-invocation}
     {:label "Actions"
      :value webhook-actions}]
    :loading
    {:sub [:webhooks/loading?]}}])

(defn new-result []
  (let [e (rf/subscribe [:webhooks/new])]
    (when @e
      [:div.card.mb-3
       [:div.card-body
        [:form
         [:h5 "Webhook Created"]
         [:div.mb-3
          [f/form-input
           {:id :url
            :label "Url"
            :value (m/api-url (str "/webhooks/" (:id @e)))
            :extra-opts {:disabled true}
            :help-msg
            "The url that will receive the POST request."}]]
         [:div.mb-3
          [f/form-input
           {:id :secret-key
            :label "Secret key"
            :value (:secret-key @e)
            :extra-opts {:disabled true}
            :help-msg
            (str "The secret key that should be passed in for HMAC verification. "
                 "Copy this an keep it secure, because you won't be able to retrieve it again.")}]]
         [co/close-btn [:webhooks/close-new]]]]])))

(defn new-webhook-btn []
  [co/icon-btn :plus-square "Add" [:webhooks/new]])

(defn page [route]
  (rf/dispatch [:webhooks/init])
  (settings/settings-page
   ::settings/webhooks
   [:<>
    [co/page-title
     [:div.d-flex
      [co/icon-text :link
       [:span.me-2 "Webhooks"]
       [co/reload-btn-sm [:webhooks/load]]]
      [:span.ms-auto [new-webhook-btn]]]]
    [:p
     "Webhooks can be invoked by external applications in order to trigger builds in "
     [:i "MonkeyCI."]]
    [new-result]
    [:div.card
     [:div.card-body
      [webhooks-table]]]]))
