(ns monkey.ci.gui.api-keys.views
  "Generic views for managing api keys, both for orgs and users"
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.forms :as f]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.time :as time]
            [monkey.ci.gui.utils :as u]
            [monkey.ci.gui.api-keys.db :as db]
            [monkey.ci.gui.api-keys.events]
            [monkey.ci.gui.api-keys.subs]
            [monkey.ci.gui.org-settings.views :as settings]
            [re-frame.core :as rf]))

(defn- add-btn [id]
  (let [e? (rf/subscribe [:tokens/editing? id])]
    [:button.btn.btn-primary
     {:on-click (u/link-evt-handler [:tokens/new id])
      :disabled (true? @e?)}
     [:<> [:span.me-2 [co/icon :plus-square]] "Add"]]))

(defn- save-btn [id]
  (let [s? (rf/subscribe [:tokens/saving? id])]
    [:button.btn.btn-primary
     {:on-click (u/link-evt-handler [:tokens/save id])
      :disabled (true? @s?)}
     [:<> [:span.me-2 [co/icon :floppy]] "Save"]]))

(defn- input-form [id]
  (when @(rf/subscribe [:tokens/editing? id])
    (let [v (rf/subscribe [:tokens/edit id])]
      [:div.card.mb-3
       [:div.card-body
        [:form
         [:h4 "New Api Key"]
         [:div.mb-3
          [f/form-input {:id :description
                         :label "Description"
                         :value (:description @v)
                         :extra-opts
                         {:on-change (u/form-evt-handler [:tokens/edit-changed id :description])}}]]
         [:div.mb-3
          [f/form-input {:id :valid-until
                         :label "Valid until"
                         :value (:valid-until @v)
                         :help-msg "Date at which this api key is no longer valid."
                         :extra-opts
                         {:type :date
                          :on-change (u/form-evt-handler [:tokens/edit-changed id :valid-until])}}]]
         [:div.d-flex.gap-2
          [save-btn id]
          [co/cancel-btn [:tokens/cancel-edit id]]]]]])))

(defn- new-token-result [id]
  (when-let [v @(rf/subscribe [:tokens/new id])]
    [:div.card.mb-3
     [:div.card-body
      [:form
       [:h4 "New Api Key"]
       [:div.mb-3
        [f/form-input {:id :description
                       :label "Description"
                       :value (:description v)
                       :extra-opts {:disabled true}}]]
       [:div.mb-3
        [f/form-input {:id :valid-until
                       :label "Valid until"
                       :value (:valid-until v)
                       :help-msg "Date at which this api key is no longer valid."
                       :extra-opts
                       {:type :date
                        :disabled true}}]]
       [:div.mb-3
        [f/form-input {:id :token
                       :label "Generated token"
                       :value (:token v)
                       :help-msg (str "The secret token.  Keep this value safe, "
                                      "it cannot be retrieved later.")
                       :extra-opts {:disabled true}}]]
       [:div.d-flex.gap-2
        [co/close-btn [:tokens/cancel-edit id]]]]]]))

(defn- token-table [conf route]
  [t/paged-table
   {:id [::api-keys (:db-id conf) ((:params->id conf) (r/path-params route))]
    :items-sub (:items-sub conf)
    :loading-sub (:loading-sub conf)
    :columns (-> [{:label "Description"
                   :value :description
                   :sorter (t/prop-sorter :description)}
                  {:label "Valid until"
                   :value (comp time/format-date time/parse-epoch :valid-until)
                   :sorter (t/prop-sorter :valid-until)}]
                 (t/add-sorting 0 :asc))}])

(defn- page [{id :db-id :as conf} route]
  (rf/dispatch [:tokens/load id (r/path-params route)])
  (settings/settings-page
   ::settings/api-keys
   [:<>
    [co/page-title [co/icon-text :key "Api keys"]]
    [:p "Api keys can be used to allow services, automated processes or the "
     [:i "MonkeyCI"] " CLI to access the REST API."]
    [input-form id]
    [new-token-result id]
    [add-btn id]
    [:div.mt-3
     [co/alerts [:loader/alerts id]]]
    [token-table conf route]]))

(def org-config
  {:db-id db/org-id
   :params->id :org-id
   :items-sub [:org-tokens/items]
   :loading-sub [:org-tokens/loading?]})

(defn org-page [route]
  (page org-config route))
