(ns monkey.ci.gui.repo.views
  (:require [monkey.ci.gui.clipboard :as cl]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.repo.events]
            [monkey.ci.gui.repo.subs]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn- build-row [b]
  [:tr
   [:td [:a {:href (r/path-for :page/build b)} (:build-id b)]]
   [:td (:timestamp b)]
   [:td [co/build-result (:result b)]]
   [:td (:message b)]])

(defn- builds []
  (rf/dispatch [:builds/load])
  (fn []
    (let [b (rf/subscribe [:repo/builds])]
      (when @b
        [:<>
         [:div.clearfix
          [:h4.float-start "Builds"]
          [:div.float-end
           [co/reload-btn [:builds/load]]]]
         [:p "Found " (count @b) " builds"]
         [:table.table.table-striped
          [:thead
           [:tr
            [:th {:scope :col} "Id"]
            [:th {:scope :col} "Time " [co/icon :caret-down-fill]]
            [:th {:scope :col} "Result"]
            [:th {:scope :col} "Commit message"]]]
          (->> @b
               (map build-row)
               (into [:tbody]))]]))))

(defn page [route]
  (rf/dispatch [:repo/load (get-in route [:parameters :path :customer-id])])
  (fn [route]
    (let [{:keys [customer-id project-id repo-id] :as p} (get-in route [:parameters :path])
          r (rf/subscribe [:repo/info project-id repo-id])]
      [l/default
       [:<>
        [:h3
         (:name @r)
         [:span.fs-6.p-1
          [cl/clipboard-copy (u/->sid p :customer-id :project-id :repo-id) "Click to save the sid to clipboard"]]]
        [:p "Repository url: " [:a {:href (:url @r)} (:url @r)]]
        [co/alerts [:repo/alerts]]
        [builds]
        [:div
         [:a {:href (r/path-for :page/customer {:customer-id customer-id})} "Back to customer"]]]])))
