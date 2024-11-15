(ns monkey.ci.gui.ssh-keys.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.ssh-keys.events]
            [monkey.ci.gui.ssh-keys.subs]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn- render-key [{desc :description :as k}]
  [:div.card
   [:div.card-body
    (when desc
      [:h4.card-title desc])]])

(defn- ssh-keys []
  (let [v (rf/subscribe [:ssh-keys/keys])]
    (->> @v
         (map render-key)
         (into [:<>
                [:p "Found " (count @v) " configured SSH keys."]]))))

(defn- global-actions []
  [:div.d-flex.gap-2
   [:button.btn.btn-outline-success
    {:title "Adds a new SSK key pair"
     :on-click (u/link-evt-handler [:ssh-keys/new])}
    [:span.me-2 [co/icon :plus-square]] "Add New"]
   [:button.btn.btn-outline-danger
    {:title "Close this screen"
     :on-click (u/link-evt-handler [:ssh-keys/cancel-all])}
    [:span.me-2 [co/icon :x-square]] "Close"]])

(defn ssh-keys-loader []
  (let [loading? (rf/subscribe [:ssh-keys/loading?])]
    (if @loading?
      [:div.card
       [:div.card-body
        [:p "Loading SSH keys..."]]]
      [ssh-keys])))

(defn page [route]
  (let [cust-id (:customer-id (r/path-params route))]
    (rf/dispatch [:ssh-keys/initialize cust-id])
    (l/default
     [:<>
      [:div.d-flex
       [:h3 "SSH Keys"]
       [co/reload-btn-sm [:ssh-keys/load cust-id] {:class :ms-auto}]]
      [:p
       "SSH keys are used to access private repositories.  When a build is triggered from a "
       "private repo, any SSH keys that are" [:b.mx-1 "configured on the customer with matching labels"]
       "are exposed to the build script."]
      [:p
       "SSH key pairs consist of a" [:b.mx-1 "private and public key"] "and can take an optional "
       "description, which can be useful for your users.  Similar to"
       [:a.ms-1 {:href (r/path-for :page/customer-params (r/path-params route))} "parameters"]
       ", they can have any labels set on them which allow them to be access by builds for "
       "repositories with the same labels."]
      [co/alerts [:ssh-keys/alerts]]
      [ssh-keys-loader]
      [global-actions]])))
