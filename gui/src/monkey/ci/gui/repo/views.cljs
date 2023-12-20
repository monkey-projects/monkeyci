(ns monkey.ci.gui.repo.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.repo.events]
            [monkey.ci.gui.repo.subs]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(defn- build-result [r]
  (let [type (condp = r
               "error" :text-bg-danger
               "success" :text-bg-success
               :text-bg-secondary)]
    [:span {:class (str "badge " (name type))} r]))

(defn- build-row [b]
  [:tr
   [:td (:id b)]
   [:td (:timestamp b)]
   [:td [build-result (:result b)]]
   [:td (:message b)]])

(defn- builds []
  (rf/dispatch [:builds/load])
  (fn []
    (let [b (rf/subscribe [:repo/builds])]
      (when @b
        [:<>
         [:h4 "Builds"]
         [:p "Found " (count @b) " builds"]
         [:table.table.table-striped
          [:thead
           [:tr
            [:th {:scope :col} "Id"]
            [:th {:scope :col} "Time"]
            [:th {:scope :col} "Result"]
            [:th {:scope :col} "Commit message"]]]
          (->> @b
               (map build-row)
               (into [:tbody]))]
         [co/reload-btn [:builds/load]]]))))

(defn page [route]
  (rf/dispatch [:repo/load (get-in route [:parameters :path :customer-id])])
  (fn [route]
    (let [{:keys [customer-id project-id repo-id]} (get-in route [:parameters :path])
          r (rf/subscribe [:repo/info project-id repo-id])]
      [l/default
       [:<>
        [:h3 (:name @r)]
        [:p "Repository url: " [:a {:href (:url @r)} (:url @r)]]
        [co/alerts [:repo/alerts]]
        [builds]
        [:div
         [:a {:href (r/path-for :page/customer {:customer-id customer-id})} "Back to customer"]]]])))
