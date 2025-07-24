(ns monkey.ci.gui.webhooks.views
  "Repository webhooks editing page"
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.repo-settings.views :as settings]
            [monkey.ci.gui.table :as t]
            [monkey.ci.gui.webhooks.subs]))

(defn webhooks-table []
  [t/paged-table
   {:id ::webhooks
    :items-sub [:repo/webhooks]
    :columns
    [{:label "Type"
      :value :type}
     {:label "Created at"
      :value :creation-time}
     {:label "Last invocation"
      :value :last-invocation}
     {:label "Actions"
      :value :actions}]}])

(defn page [route]
  (settings/settings-page
   ::settings/webhooks
   [:<>
    [co/page-title
     [co/icon-text :link
      [:span.me-2 "Webhooks"]
      [co/reload-btn-sm [:webhooks/reload]]]]
    [:p
     "Webhooks can be invoked by external applications in order to trigger builds in "
     [:i "MonkeyCI."]]
    [:div.card
     [:div.card-body
      [webhooks-table]]]]))
