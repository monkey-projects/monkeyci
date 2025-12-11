(ns monkey.ci.gui.org-settings.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.tabs :as tabs]))

(def tab-id ::settings-tabs)

(def tab-headers
  [{:id ::general
    :header "General"
    :link :page/org-settings}
   {:id ::billing
    :header "Billing"
    :link :page/org-billing}
   {:id ::params
    :header "Parameters"
    :link :page/org-params}
   {:id ::ssh-keys
    :header "SSH keys"
    :link :page/org-ssh-keys}
   {:id ::api-keys
    :header "Api keys"
    :link :page/org-api-keys}])

(defn settings-tabs [active]
  [:div.col-md-3
   (tabs/settings-tabs tab-headers active)])

(defn settings-content [content]
  [:div.col-md-9 content])

(defn settings-page
  "Renders organization settings page for given id"
  [id content]
  (l/default
   [:div.row.mb-3
    [settings-tabs id]
    [settings-content content]]))

