(ns monkey.ci.gui.ssh-keys.views
  (:require [monkey.ci.gui.components :as co]
            [monkey.ci.gui.layout :as l]
            [monkey.ci.gui.labels :as lbl]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.ssh-keys.events :as e]
            [monkey.ci.gui.ssh-keys.subs]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn- delete-set-btn [k]
  [:button.btn.btn-outline-danger
   {:title "Deletes this ssh key set"
    :on-click (u/link-evt-handler [:ssh-keys/delete-set k])}
   [:span.me-2 [co/icon :trash]] "Delete"])

(defn- view-set-actions [ks]
  [:div.d-flex.gap-2
   [:button.btn.btn-outline-success
    {:title "Edits this parameter set"
     :on-click (u/link-evt-handler [:ssh-keys/edit-set ks])}
    [:span.me-2 [co/icon :pencil-square]] "Edit"]
   [delete-set-btn ks]])

(defn- show-key [{desc :description pk :private-key :as k}]
  [:div.card.my-2
   [:div.card-body
    (when desc
      [:h4.card-title desc])
    [:div.row
     [:div.col-2 [:b "Public key:"]]
     [:div.col-10 (:public-key k)]]
    [:div.row.mb-2
     [:div.col-2 [:b "Private key:"]]
     [:div.col-10 (str "..." (subs pk (- (count pk) 4)))]]
    [lbl/label-filters-desc (:label-filters k)]
    [:div.mt-2
     [view-set-actions k]]]])

(defn- key-set-actions [k]
  [:div.d-flex.gap-2
   [:button.btn.btn-primary
    {:on-click (u/link-evt-handler [:ssh-keys/save-set k])}
    [:span.me-2 [co/icon :save]] "Save Changes"]
   [:button.btn.btn-outline-primary
    {:title "Discards all unsaved changes"
     :on-click (u/link-evt-handler [:ssh-keys/cancel-set k])}
    [:span.me-2 [co/icon :x-square]] "Cancel"]
   (when-not (e/new-set? k)
     [delete-set-btn k])])

(defn- input-props [k id & [desc]]
  {:id id
   :placeholder desc
   :value (id k)
   :on-change (u/form-evt-handler [:ssh-keys/prop-changed k id])})

(defn- edit-key [k]
  (let [id (e/set-id k)]
    [:div.card.my-2
     [:div.card-body
      [:div.mb-3
       [:label.form-label {:for :description} "Description"]
       [:input.form-control (input-props k :description "Optional human readable description")]]
      [:div.mb-3
       [:label.form-label {:for :public-key} "Public key*"]
       [:textarea.form-control
        (assoc (input-props k :public-key)
               :rows 2)]]
      [:div.mb-3
       [:label.form-label {:for :public-key} "Private key*"]
       [:textarea.form-control
        (assoc (input-props k :private-key) :rows 8)]]
      [:div.mb-2 [lbl/edit-label-filters (e/labels-id id)]]
      [key-set-actions k]]]))

(defn- show-or-edit-key [k]
  (if (:editing? k)
    [edit-key k]
    [show-key k]))

(defn- ssh-keys []
  (let [v (rf/subscribe [:ssh-keys/display-keys])]
    (->> @v
         (map show-or-edit-key)
         (into [:div.mb-2
                [:p "Found " (count @v) " configured SSH keys."]]))))

(defn- global-actions [cust-id]
  [:div.d-flex.gap-2.mt-2
   [:button.btn.btn-outline-success
    {:title "Adds a new SSK key pair"
     :on-click (u/link-evt-handler [:ssh-keys/new])}
    [:span.me-2 [co/icon :plus-square]] "Add New"]
   [:a.btn.btn-outline-danger
    {:title "Close this screen"
     :href (r/path-for :page/customer {:customer-id cust-id})}
    [:span.me-2 [co/icon :x-square]] "Close"]])

(defn ssh-keys-loader []
  (let [loading? (rf/subscribe [:ssh-keys/loading?])]
    (if @loading?
      [:div.card
       [:div.card-body
        [:p "Loading SSH keys..."]]]
      [ssh-keys])))

(defn page [route]
  (let [cust-id (:customer-id (r/path-params route))]
    (rf/dispatch [:ssh-keys/initialize cust-id])
    (l/default
     [:<>
      [:div.d-flex
       [:h3 "SSH Keys"]
       [co/reload-btn-sm [:ssh-keys/load cust-id] {:class :ms-auto}]]
      [:p
       "SSH keys are used to access private repositories.  When a build is triggered from a "
       "private repo, any SSH keys that are" [:b.mx-1 "configured on the organization with matching labels"]
       "are exposed to the build script."]
      [:p
       "SSH key pairs consist of a" [:b.mx-1 "private and public key"] "and can take an optional "
       "description, which can be useful for your users.  Similar to"
       [:a.ms-1 {:href (r/path-for :page/customer-params (r/path-params route))} "parameters"]
       ", they can have any labels set on them which allow them to be accessed by builds for "
       "repositories with the same labels."]
      [co/alerts [:ssh-keys/alerts]]
      [ssh-keys-loader]
      [global-actions cust-id]])))
