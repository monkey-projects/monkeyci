(ns monkey.ci.gui.webhooks.views
  "Repository webhooks editing page"
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.clipboard :as cl]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.modals :as modals]
            [monkey.ci.gui.repo-settings.views :as settings]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.utils :as u]
            [monkey.ci.gui.webhooks.events :as e]
            [monkey.ci.gui.webhooks.subs]
            [re-frame.core :as rf]))

(def delete-modal-id ::delete-wh-confirm)

(defn- webhook-url [type wh]
  ;; TODO Also allow for bitbucket urls
  (m/api-url (str "/webhook/" (name type) "/" (:id wh))))

(def github-url (partial webhook-url :github))
(def bitbucket-url (partial webhook-url :bitbucket))

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

(defn- copy-url-dropdown [wh]
  [:ul.dropdown-menu
   [:li
    [:a.dropdown-item
     {:href "#"
      :on-click (cl/copy-handler (github-url wh))}
     "Copy GitHub url"]
    [:a.dropdown-item
     {:href "#"
      :on-click (cl/copy-handler (bitbucket-url wh))}
     "Copy BitBucket url"]]])

(defn- copy-url-btn [{:keys [id] :as wh}]
  [:div.btn-group
   [:button.btn.btn-outline-primary.btn-icon.btn-sm.dropdown-toggle
    {:title "Copy the webhook url to clipboard"
     :type :button
     :data-bs-toggle :dropdown}
    [:span.ms-1 [co/icon :clipboard]]]
   [copy-url-dropdown wh]])

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
      :value (comp co/date-time :creation-time)}
     {:label "Last invocation"
      :value (comp co/date-time :last-inv-time)}
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
          [:label.form-label {:for :id} "Id"]
          [:div.input-group
           [:input.form-control
            {:id :id
             :name :id
             :aria-describedby :id-help
             :default-value (:id @e)
             :disabled true}]
           [:button.btn.btn-primary.btn-icon.dropdown-toggle
            {:title "Copy the webhook url to clipboard"
             :type :button
             :data-bs-toggle :dropdown}
            [:span.ms-2 [co/icon :clipboard]]]
           ;; TODO If we can determine the type from the repo url, don't show dropdown
           [copy-url-dropdown @e]]
          [:span.form-text {:id :id-help}
           "The unique webhook id, used to construct the url."]]
         [:div.mb-3
          [f/form-input
           {:id :secret-key
            :label "Secret key"
            :value (:secret-key @e)
            :extra-opts {:disabled true}
            :help-msg
            (str "The secret key that should be passed in for HMAC verification. "
                 "Copy this and keep it secure, because you won't be able to retrieve it again.")}]]
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
     [:div.card.mb-3
      [:div.card-body
       [a/component [:webhooks/alerts]]
       [webhooks-table]]]
     [co/close-btn [:route/goto :page/repo (r/path-params route)]]])
   [confirm-delete-modal]])
