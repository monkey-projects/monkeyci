(ns monkey.ci.gui.params.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.customer.events]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.params.events]
            [monkey.ci.gui.params.subs]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn- param-form [parent-idx idx {:keys [name value]}]
  [:div.row.mb-2
   [:div.col-md-3
    [:input.form-control
     {:type :input
      :id (str "label-" idx)
      :value name}]]
   [:div.col-md-8
    [:textarea.form-control
     {:id (str "value-" idx)
      :value value}]]
   [:div.col-md-1
    [:button.btn.btn-outline-danger
     {:title "Delete parameter"
      :on-click (u/link-evt-handler [:param/delete parent-idx idx])}
     [co/icon :trash]]]])

(defn- label-filters-desc [lf]
  (letfn [(disj-desc [idx items]
            (into [:li
                   (when (pos? idx)
                     [:b.me-1 "AND"])]
                  (map-indexed conj-desc items)))
          (conj-desc [idx {:keys [label value]}]
            [:span
             (when (pos? idx)
               [:b.me-1 "OR"])
             (str label " = " value)])]
    (if (empty? lf)
      [:i "Applies to all builds for this customer."]
      [:<>
       [:i "Applies to all builds where:"]
       (->> lf
            (map-indexed disj-desc)
            (into [:ul]))])))

(defn- params-actions []
  [:<>
   [:button.btn.btn-primary.me-2
    [:span.me-2 [co/icon :save]] "Save Changes"]
   [:button.btn.btn-outline-primary.me-2
    {:title "Discards all unsaved changes"}
    [:span.me-2 [co/icon :x-square]] "Cancel"]
   [:button.btn.btn-outline-success.me-2
    {:title "Adds a new parameter to this set"}
    [:span.me-2 [co/icon :plus-square]] "Add Row"]
   [:button.btn.btn-outline-danger
    {:title "Deletes all parameters in this set"}
    [:span.me-2 [co/icon :trash]] "Delete"]])

(defn- params-card [idx {:keys [description label-filters parameters]}]
  [:div.card.mb-4
   [:div.card-body
    (when description
      [:h5.card-title description])
    [:p.card-text
     [:div.row
      [:div.col [:h6 "Label"]]
      [:div.col [:h6 "Value"]]]
     (->> parameters
          (map-indexed (partial param-form idx))
          (into [:form]))]
    [:div.card-body
     [:div.col [label-filters-desc label-filters]]
     [params-actions]]]])

(defn- params-list []
  (let [loading? (rf/subscribe [:params/loading?])
        params (rf/subscribe [:customer/params])]
    (if @loading?
      [:p "Retrieving customer build parameters..."]
      [:div
       (->> @params
            (map-indexed params-card)
            (into [:<>]))
       [:button.btn.btn-primary.mt-2
        {:title "Adds a new empty parameter set"}
        [:span.me-2 [co/icon :plus-square]] "Add Set"]])))

(defn page
  "Customer parameters overview"
  [route]
  (let [id (-> route (r/path-params) :customer-id)]
    (rf/dispatch [:customer/maybe-load id])
    (rf/dispatch [:params/load id])
    (l/default
     [:<>
      [:h3 "Build Parameters"]
      [co/alerts [:params/alerts]]
      [params-list]])))
