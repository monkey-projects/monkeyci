(ns monkey.ci.gui.repo-settings.views
  (:require [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.tabs :as tabs]))

(def tab-headers
  [{:id ::general
    :header "General"
    :link :page/repo-settings}
   {:id ::webhooks
    :header "Webhooks"
    :link :page/webhooks}])

(defn settings-tabs [active]
  [:div.col-md-2
   (tabs/settings-tabs tab-headers active)])

(defn settings-content [content]
  [:div.col-md-10 content])

(defn settings-page
  "Renders repo settings page for header id"
  [id content]
  (l/default
   [:div.row.mb-3
    [settings-tabs id]
    [settings-content content]]))
