(ns monkey.ci.gui.repo.views
  (:require [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.repo.subs]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(defn page [route]
  (let [{:keys [customer-id project-id id]} (get-in route [:parameters :path])
        r (rf/subscribe [:repo/info project-id id])]
    [l/default
     [:<>
      [:h3 (:name @r)]
      [:p "Repository url: " [:a {:href (:url @r)} (:url @r)]]
      [:h4 "Builds"]
      [:a {:href (r/path-for :page/customer {:id customer-id})} "Back to customer"]]]))
