(ns monkey.ci.gui.org-settings.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.tabs :as tabs]
            [re-frame.core :as rf]))

(def tab-id ::settings-tabs)

(def tab-headers
  [{:id ::general
    :header "General"
    :link :page/org-settings}
   {:id ::params
    :header "Parameters"
    :link :page/org-params}
   {:id ::ssh-keys
    :header "SSH keys"
    :link :page/org-ssh-keys}])

(defn- render-header [active path {:keys [id header link]}]
  [:li.nav-item
   [:a.nav-link (cond-> {:href (r/path-for link path)}
                  (= id active) (assoc :class :active))
    header]])

(defn settings-tabs [active]
  (let [r (rf/subscribe [:route/current])]
    [:div.col-md-3.mb-3
     (->> tab-headers
          (map (partial render-header active (r/path-params @r)))
          (into [:div.nav.nav-tabs.nav-vertical.nav-link-gray]))]))

(defn settings-content [content]
  [:div.col-md-9 content])

(defn settings-page
  "Renders organization settings page for given id"
  [id content]
  (l/default
   [:div.row
    [settings-tabs id]
    [settings-content content]]
   #_[co/close-btn [:route/goto :page/org (r/path-params route)]]))

