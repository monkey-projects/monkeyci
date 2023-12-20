(ns monkey.ci.gui.customer.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.customer.events]
            [monkey.ci.gui.customer.subs]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(defn- load-customer [id]
  (rf/dispatch [:customer/load id]))

(defn- show-repo [c p r]
  [:div.repo.card-body
   [:div.float-start
    [:b {:title (:id r)} (:name r)]
    [:p "Url: " [:a {:href (:url r)} (:url r)]]]
   [:a.btn.btn-primary.float-end
    {:href (r/path-for :page/repo {:customer-id (:id c)
                                   :project-id (:id p)
                                   :repo-id (:id r)})}
    [co/icon :three-dots-vertical] " Details"]])

(defn- show-project [cust p]
  (->> (:repos p)
       (sort-by :name)
       (map (partial show-repo cust p))
       (into
        [:div.project.card.mb-3
         [:div.card-header
          [:h5.card-title {:title (:id p)} (:name p)]]])))

(defn- customer-details []
  (let [c (rf/subscribe [:customer/info])]
    (->> (:projects @c)
         (sort-by :name)
         (map (partial show-project @c))
         (into [:<>
                [:h3 "Customer " (:name @c)]]))))

(defn page
  "Customer overview page"
  [route]
  (let [id (get-in route [:parameters :path :customer-id])]
    (load-customer id)
    (l/default
     [:div
      [co/alerts [:customer/alerts]]
      [co/reload-btn [:customer/load id]]
      [customer-details]])))
