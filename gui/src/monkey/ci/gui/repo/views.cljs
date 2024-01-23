(ns monkey.ci.gui.repo.views
  (:require [monkey.ci.gui.clipboard :as cl]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.repo.events]
            [monkey.ci.gui.repo.subs]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.time :as t]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn- reformat [x]
  (some-> (t/parse x)
          (t/format-datetime)))

(defn- elapsed [b]
  (t/format-seconds (int (/ (u/build-elapsed b) 1000))))

(defn- build-row [b]
  [:tr
   [:td [:a {:href (r/path-for :page/build b)} (:build-id b)]]
   [:td.text-end (reformat (:timestamp b))]
   [:td (elapsed b)]
   [:td (:source b)]
   [:td (:ref b)]
   [:td.text-center [co/build-result (:result b)]]
   [:td (some-> (:message b) (subs 0 20))]])

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
            [:th {:scope :col} "Elapsed"]
            [:th {:scope :col} "Trigger"]
            [:th {:scope :col} "Ref"]
            [:th {:scope :col} "Result"]
            [:th {:scope :col} "Commit message"]]]
          (->> @b
               (map build-row)
               (into [:tbody]))]]))))

(defn page [route]
  (rf/dispatch [:repo/load (get-in route [:parameters :path :customer-id])])
  (fn [route]
    (let [{:keys [customer-id repo-id] :as p} (get-in route [:parameters :path])
          r (rf/subscribe [:repo/info repo-id])]
      [l/default
       [:<>
        [:h3
         (:name @r)
         [:span.fs-6.p-1
          [cl/clipboard-copy (u/->sid p :customer-id :repo-id) "Click to save the sid to clipboard"]]]
        [:p "Repository url: " [:a {:href (:url @r)} (:url @r)]]
        [co/alerts [:repo/alerts]]
        [builds]
        [:div
         [:a {:href (r/path-for :page/customer {:customer-id customer-id})} "Back to customer"]]]])))
