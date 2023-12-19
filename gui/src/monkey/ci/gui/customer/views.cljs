(ns monkey.ci.gui.customer.views
  (:require [monkey.ci.gui.customer.events]
            [monkey.ci.gui.customer.subs]
            [monkey.ci.gui.layout :as l]
            [re-frame.core :as rf]))

(defn render-alert [{:keys [type message]}]
  [:div {:class (str "alert alert-" (name type))} message])

(defn alerts []
  (let [s (rf/subscribe [:customer/alerts])]
    (when (not-empty @s)
      (->> @s
           (map render-alert)
           (into [:<>])))))

(defn- load-customer [id]
  (rf/dispatch [:customer/load id]))

(defn- show-repo [r]
  [:div.repo.card-body
   [:div.float-start
    [:b {:title (:id r)} (:name r)]
    [:p "Url: " [:a {:href (:url r)} (:url r)]]]
   [:button.btn.btn-primary.float-end "Details"]])

(defn- show-project [p]
  (->> (:repos p)
       (map show-repo)
       (into [:div.project.card.mb-3
              [:div.card-header
               [:h5.card-title {:title (:id p)} (:name p)]]])))

(defn- customer-details []
  (let [c (rf/subscribe [:customer/info])]
    (->> (:projects @c)
         (map show-project)
         (into [:<>
                [:h3 "Customer " (:name @c)]]))))

(defn page
  "Customer overview page"
  [route]
  (let [id (get-in route [:parameters :path :id])]
    (load-customer id)
    (l/default
     [:div
      [alerts]
      [:button.btn.btn-primary {:on-click #(load-customer id)} "Reload"]
      [customer-details]])))
