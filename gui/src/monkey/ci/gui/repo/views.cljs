(ns monkey.ci.gui.repo.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.repo.events]
            [monkey.ci.gui.repo.subs]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(defn- builds []
  (rf/dispatch [:builds/load])
  (let [b (rf/subscribe [:repo/builds])]
    [:<>
     [:h4 "Builds"]
     [:p "Found " (count @b) " builds"]]))

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
        [:a {:href (r/path-for :page/customer {:customer-id customer-id})} "Back to customer"]]])))
