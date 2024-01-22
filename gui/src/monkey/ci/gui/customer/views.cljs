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
                                   :repo-id (:id r)})}
    [co/icon :three-dots-vertical] " Details"]])

(defn- show-project [cust [p repos]]
  (->> repos
       (sort-by :name)
       (map (partial show-repo cust p))
       (into
        [:div.project.card.mb-3
         [:div.card-header
          [:h5.card-title p]]])))

(defn- project-lbl [r]
  (->> (:labels r)
       (filter (comp (partial = "project") :name))
       (map :value)
       (first)))

(defn- customer-details [id]
  (let [c (rf/subscribe [:customer/info])]
    (->> (:repos @c)
         (group-by project-lbl)
         (sort-by first)
         (map (partial show-project @c))
         (into [:<>
                [:div.clearfix.mb-3
                 [:h3.float-start "Customer " (:name @c)]
                 [co/reload-btn [:customer/load id] {:class :float-end}]]]))))

(defn page
  "Customer overview page"
  [route]
  (let [id (get-in route [:parameters :path :customer-id])]
    (load-customer id)
    (l/default
     [:div
      [co/alerts [:customer/alerts]]
      [customer-details id]])))
