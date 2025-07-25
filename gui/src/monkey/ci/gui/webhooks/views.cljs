(ns monkey.ci.gui.webhooks.views
  "Repository webhooks editing page"
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.clipboard :as cl]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.modals :as modals]
            [monkey.ci.gui.repo-settings.views :as settings]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.utils :as u]
            [monkey.ci.gui.webhooks.events :as e]
            [monkey.ci.gui.webhooks.subs]
            [re-frame.core :as rf]))

(def delete-modal-id ::delete-wh-confirm)

(defn- webhook-url [wh]
  ;; TODO Also allow for bitbucket urls
  (m/api-url (str "/webhook/github/" (:id wh))))

(defn confirm-delete-modal
  []
  [modals/modal
   delete-modal-id
   [:h4 "Confirmation"]
   [:div
    [:p "Are you sure you want to delete this webhook?"]
    [:p "This operation cannot be undone."]]
   [:div.d-flex.gap-2
    [:button.btn.btn-danger
     {:title "Confirm delete"
      :data-bs-dismiss "modal"
      :on-click (u/link-evt-handler [:webhooks/delete])}
     [:span.me-2 co/delete-icon] "Yes, Delete!"]
    [modals/modal-dismiss-btn
     [:span [:span.me-2 co/cancel-icon] "Oops, No"]]]])

(defn- copy-url-btn [wh]
  [:button.btn.btn-outline-primary.btn-icon.btn-sm
   {:title "Copy the webhook url to clipboard"}
   [cl/clipboard-copy (webhook-url wh) nil]])

(defn- delete-btn [{:keys [id]}]
  (let [d? (rf/subscribe [:webhooks/deleting? id])]
    [:button.btn.btn-danger.btn-icon.btn-sm
     (cond-> {:id (str "delete-btn-" id)
              :title "Delete this webhook"
              :data-bs-toggle :modal
              :data-bs-target (u/->dom-id delete-modal-id)
              :data-bs-wh-id id
              ;; Store the webhook id, so we know which one to delete in the modal
              :on-click (u/link-evt-handler [:webhooks/delete-confirm id])}
       @d? (assoc :disabled true))
     co/delete-icon]))

(defn- webhook-actions [wh]
  [:div.d-flex.gap-2
   [copy-url-btn wh]
   [delete-btn wh]])

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
            :value (webhook-url @e)
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
  [:<>
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
      [:i "MonkeyCI."]  " See the " [co/docs-link "articles/triggers" "webhook documentation"]
      " for details."]
     [new-result]
     [:div.card
      [:div.card-body
       [a/component [:webhooks/alerts]]
       [webhooks-table]]]])
   [confirm-delete-modal]])
