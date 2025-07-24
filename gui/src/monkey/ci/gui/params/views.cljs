(ns monkey.ci.gui.params.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.org.events]
            [monkey.ci.gui.labels :as lbl]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.org-settings.views :as settings]
            [monkey.ci.gui.params.events :as e]
            [monkey.ci.gui.params.subs]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn- param-form [set-id param-idx]
  (let [{:keys [name value]} @(rf/subscribe [:org/param set-id param-idx])]
    [:div.row.mb-2
     [:div.col-md-3
      [:input.form-control
       {:type :input
        :id (str "label-" param-idx)
        :value name
        :on-change (u/form-evt-handler [:params/label-changed set-id param-idx])}]]
     [:div.col-md-8
      [:textarea.form-control
       {:id (str "value-" param-idx)
        :value value
        :on-change (u/form-evt-handler [:params/value-changed set-id param-idx])}]]
     [:div.col-md-1
      [:button.btn.btn-outline-danger
       {:title "Delete parameter"
        :on-click (u/link-evt-handler [:params/delete-param set-id param-idx])}
       [co/icon :trash]]]]))

(defn- label-filters-desc [lf]
  (letfn [(disj-desc [idx items]
            (into [:li
                   (when (pos? idx)
                     [:b.me-1 "OR"])]
                  (map-indexed conj-desc items)))
          (conj-desc [idx {:keys [label value]}]
            [:span
             (when (pos? idx)
               [:b.mx-1 "AND"])
             (str label " = " value)])]
    (if (empty? lf)
      [:i "Applies to all builds for this organization."]
      [:<>
       [:i "Applies to all builds where:"]
       (->> lf
            (map-indexed disj-desc)
            (into [:ul]))])))

(defn- params-actions [{:keys [id] :as p}]
  [:div.d-flex.gap-2
   [:button.btn.btn-primary
    {:on-click (u/link-evt-handler [:params/save-set id])}
    [:span.me-2 [co/icon :save]] "Save Changes"]
   [:button.btn.btn-outline-primary
    {:title "Discards all unsaved changes"
     :on-click (u/link-evt-handler [:params/cancel-set id])}
    [:span.me-2 [co/icon :x-square]] "Cancel"]
   (when-not (e/new-set? p)
     [:button.btn.btn-outline-danger
      {:title "Deletes all parameters in this set"
       :on-click (u/link-evt-handler [:params/delete-set id])}
      [:span.me-2 [co/icon :trash]] "Delete"])])

(defn- edit-params-card [{:keys [id] :as p}]
  (let [param (rf/subscribe [:params/editing id])]
    [:div.card.mb-4
     [:div.card-body
      [:div.row.mb-2
       [:div.col-md-3
        [:h6 "Description"]]
       [:div.col-md-8
        [:input.form-control
         {:id (str "description-" id)
          :value (:description @param)
          :on-change (u/form-evt-handler [:params/description-changed id])}]]]
      [:div.row
       [:div.col-md-3 [:h6 "Name"]]
       [:div.col-md-8 [:h6 "Value"]]]
      (->> (:parameters @param)
           (map-indexed (partial param-form id))
           (into [:form]))
      [:button.btn.btn-outline-success.mb-2
       {:title "Adds a new parameter to this set"
        :on-click (u/link-evt-handler [:params/new-param id])}
       [:span.me-2 [co/icon :plus-square]] "Add Parameter"]
      [:div.mb-2 [lbl/edit-label-filters (e/labels-id id)]]
      [params-actions p]
      [:div.mt-2
       [co/alerts [:params/set-alerts id]]]]]))

(defn- delete-set-btn [id]
  (let [deleting? (rf/subscribe [:params/set-deleting? id])]
    [:button.btn.btn-outline-danger
     (cond-> {:title "Deletes all parameters in this set"
              :on-click (u/link-evt-handler [:params/delete-set id])}
       @deleting? (assoc :disabled true))
     [:span.me-2 [co/icon :trash]] "Delete"]))

(defn- view-set-actions [{:keys [id]}]
  [:div.d-flex.gap-2
   [:button.btn.btn-outline-success
    {:title "Edits this parameter set"
     :on-click (u/link-evt-handler [:params/edit-set id])}
    [:span.me-2 [co/icon :pencil-square]] "Edit"]
   [delete-set-btn id]])

(defn- view-params-card [{:keys [id description label-filters parameters] :as p}]
  [:div.card.mb-4
   [:div.card-body
    (when description
      [:h5.card-title description])
    [:div.row
     [:div.col
      [:h6 "Parameters"]
      (->> parameters
           (map :name)
           (map (partial into [:li]))
           (into [:ul]))
      [lbl/label-filters-desc label-filters]]
     [:div.col
      [:div.float-end
       [view-set-actions p]]]]
    [:div.mt-1
     [co/alerts [:params/set-alerts id]]]]])

(defn- params-card [{:keys [id] :as p}]
  (let [editing? (rf/subscribe [:params/editing? id])]
    (if @editing?
      [edit-params-card p]
      [view-params-card p])))

(defn- global-actions []
  [:div.d-flex.gap-2
   [:button.btn.btn-outline-success
    {:title "Adds a new empty parameter set"
     :on-click (u/link-evt-handler [:params/new-set])}
    [:span.me-2 [co/icon :plus-square]] "Add Set"]
   [:button.btn.btn-outline-danger
    {:title "Close this screen"
     :on-click (u/link-evt-handler [:params/cancel-all])}
    [:span.me-2 [co/icon :x-square]] "Close"]])

(defn- loading-card []
  [:div.card
   [:div.card-body
    [:h6 "Parameters"]
    ;; Generate some placeholders
    (->> [100 120 90]
         (map (fn [v] [:li [:div.placeholder {:style {:width (str v "px")}}]]))
         (into [:ul.placeholder-glow]))]])

(defn- params-list []
  (let [loading? (rf/subscribe [:params/loading?])
        params (rf/subscribe [:org/params])
        new-sets (rf/subscribe [:params/new-sets])]
    (if @loading?
      [loading-card]
      [:<>
       (->> (map edit-params-card @new-sets)
            (concat (map params-card @params))
            (into [:<>]))
       [:div.card
        [:div.card-body
         [global-actions]]]])))

(defn page
  "Organization parameters overview"
  [route]
  (let [id (-> route (r/path-params) :org-id)]
    (rf/dispatch [:org/maybe-load id])
    (rf/dispatch [:params/load id])
    (settings/settings-page
     ::settings/params
     [:<>
      [co/page-title [co/icon-text :gear "Build Parameters"]]
      [:p
       "Parameters can be used by builds for " [:b "sensitive information"] ", or dynamically changing "
       "data, like hostnames or file paths. They obey the following rules:"]
      [:ul
       [:li "Parameters are grouped in " [:b "parameter sets"] "."]
       [:li "Each set can be accessed by builds according to the configured " [:b "label filters"]]
       [:li "Builds from repositories with matching labels can access those parameters."]
       [:li "A set can be accessed by multiple repositories, and builds from a repository can potentially "
        "access multiple sets."]
       [:li "When a parameter set has " [:b "no label filters"] ", all your builds can read that set."]]
      
      [co/alerts [:params/alerts]]
      [params-list]])))
