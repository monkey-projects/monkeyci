(ns monkey.ci.gui.params.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.customer.events]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.params.events]
            [monkey.ci.gui.params.subs]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn- param-form [set-idx param-idx]
  (let [{:keys [name value] :as p} @(rf/subscribe [:customer/param set-idx param-idx])]
    [:div.row.mb-2
     [:div.col-md-3
      [:input.form-control
       {:type :input
        :id (str "label-" param-idx)
        :value name
        :on-change (u/form-evt-handler [:params/label-changed set-idx param-idx])}]]
     [:div.col-md-8
      [:textarea.form-control
       {:id (str "value-" param-idx)
        :value value
        :on-change (u/form-evt-handler [:params/value-changed set-idx param-idx])}]]
     [:div.col-md-1
      [:button.btn.btn-outline-danger
       {:title "Delete parameter"
        :on-click (u/link-evt-handler [:params/delete-param set-idx param-idx])}
       [co/icon :trash]]]]))

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

(defn- params-actions [idx]
  [:<>
   #_[:button.btn.btn-primary.me-2
    {:on-click (u/link-evt-handler [:params/save-set])}
    [:span.me-2 [co/icon :save]] "Save Changes"]
   [:button.btn.btn-outline-primary.me-2
    {:title "Discards all unsaved changes"
     :on-click (u/link-evt-handler [:params/cancel-set idx])}
    [:span.me-2 [co/icon :x-square]] "Cancel"]
   [:button.btn.btn-outline-success.me-2
    {:title "Adds a new parameter to this set"
     :on-click (u/link-evt-handler [:params/new-param idx])}
    [:span.me-2 [co/icon :plus-square]] "Add Row"]
   [:button.btn.btn-outline-danger
    {:title "Deletes all parameters in this set"
     :on-click (u/link-evt-handler [:params/delete-set idx])}
    [:span.me-2 [co/icon :trash]] "Delete"]])

(defn- params-card [idx {:keys [description label-filters parameters]}]
  [:div.card.mb-4
   [:div.card-body
    #_(when description
      [:h5.card-title description])
    [:div.row.mb-2
     [:div.col-md-3
      [:h6 "Description"]]
     [:div.col-md-8
      [:input.form-control
       {:id (str "description-" idx)
        :value description
        :on-changed (u/form-evt-handler [:params/description-changed idx])}]]]
    [:p.card-text
     [:div.row
      [:div.col-md-3 [:h6 "Label"]]
      [:div.col-md-8 [:h6 "Value"]]]
     (->> parameters
          (map-indexed (partial param-form idx))
          (into [:form]))]
    [:div.card-body
     ;; TODO Make this editable
     [:div.mb-2 [label-filters-desc label-filters]]
     [params-actions idx]]]])

(defn- params-list []
  (let [loading? (rf/subscribe [:params/loading?])
        params (rf/subscribe [:customer/params])]
    (if @loading?
      [:p "Retrieving customer build parameters..."]
      [:div
       (->> @params
            (map-indexed params-card)
            (into [:<>]))
       [:div.mt-2
        [:button.btn.btn-primary.me-2
         {:on-click (u/link-evt-handler [:params/save-all])}
         [:span.me-2 [co/icon :save]] "Save All Changes"]
        [:button.btn.btn-outline-primary.me-2
         {:title "Discards all unsaved changes"
          :on-click (u/link-evt-handler [:params/cancel-all])}
         [:span.me-2 [co/icon :x-square]] "Cancel"]
        [:button.btn.btn-outline-success
         {:title "Adds a new empty parameter set"
          :on-click (u/link-evt-handler [:params/new-set])}
         [:span.me-2 [co/icon :plus-square]] "Add Set"]]])))

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
