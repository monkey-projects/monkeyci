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

(defn page
  "Customer overview page"
  [route]
  (let [id (get-in route [:parameters :path :id])]
    (load-customer id)
    (l/default
     [:div
      [:h3 "Customer " id]
      [alerts]
      [:button.btn.btn-primary {:on-click #(load-customer id)} "Reload"]
      [:p "Customer overview goes here"]])))
