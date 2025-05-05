(ns monkey.ci.gui.labels
  "Label editing components"
  (:require [medley.core :as mc]
            [monkey.ci.gui.components :as co]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn get-labels [db id]
  (get-in db [::labels id]))

(defn set-labels [db id l]
  (assoc-in db [::labels id] l))

(defn clear-labels [db id]
  (update db ::labels dissoc id))

(defn- update-labels [db id f & args]
  (apply update-in db [::labels id] f args))

(rf/reg-event-db
 :labels/add-disjunction
 (fn [db [_ id]]
   (update-labels db id concat [[{:label "" :value ""}]])))

(rf/reg-event-db
 :labels/add-conjunction
 (fn [db [_ id or-idx and-idx]]
   (update-labels db id u/update-nth or-idx (partial mc/insert-nth and-idx {:label "" :value ""}))))

(rf/reg-event-db
 :labels/remove-conjunction
 (fn [db [_ id or-idx and-idx]]
   (letfn [(remove-and-prune [lbl]
             (-> lbl
                 (u/update-nth or-idx (partial mc/remove-nth and-idx))
                 (as-> r (filterv not-empty r))))]
     (update-labels db id remove-and-prune))))

(defn- set-lbl-prop [db id or-idx and-idx k v]
  (update-labels db id u/update-nth or-idx u/update-nth and-idx assoc k v))

(rf/reg-event-db
 :labels/label-changed
 (fn [db [_ id or-idx and-idx new-v]]
   (set-lbl-prop db id or-idx and-idx :label new-v)))

(rf/reg-event-db
 :labels/value-changed
 (fn [db [_ id or-idx and-idx new-v]]
   (set-lbl-prop db id or-idx and-idx :value new-v)))

(rf/reg-sub
 :labels/edit
 (fn [db [_ id]]
   (get-labels db id)))

(defn- row-actions [id or-idx and-idx]
  [:div.btn-group
   [:button.btn.btn-outline-primary
    {:title "Add row below this one"
     :on-click (u/link-evt-handler [:labels/add-conjunction id or-idx (inc and-idx)])}
    [co/icon :plus-square]]
   [:button.btn.btn-outline-danger
    {:title "Remove row"
     :on-click (u/link-evt-handler [:labels/remove-conjunction id or-idx and-idx])}
    [co/icon :trash]]])

(defn- label-filter-row-editor [id lf or-idx and-idx]
  [:div.row
   (when (odd? or-idx)
     {:class :bg-light})
   ;; First two columns: indicate if it's a conjunction or disjunction
   (cond
     (and (pos? or-idx) (zero? and-idx))
     [:div.col-1.align-self-center "OR"]
     (pos? and-idx)
     [:div.col-1.offset-1.align-self-center "AND"])
   [:div.col-2
    (cond
      (and (zero? or-idx) (zero? and-idx))
      {:class :offset-2}
      (zero? and-idx)
      {:class :offset-1})
    [:input.form-control.mb-1
     {:value (:label lf)
      :on-change (u/form-evt-handler [:labels/label-changed id or-idx and-idx])}]]
   [:div.col-auto "="]
   [:div.col-6
    [:input.form-control.mb-1
     {:value (:value lf)
      :on-change (u/form-evt-handler [:labels/value-changed id or-idx and-idx])}]]
   [:div.col-auto
    [row-actions id or-idx and-idx]]])

(defn- edit-label-filter-disjunction
  "Renders editor for a single disjunction row.  Each disjunction row can consist of
   one or more conjunctions (logical `AND`)"
  [id di d]
  (->> d
       (map-indexed (fn [ci r]
                      [label-filter-row-editor id r di ci]))
       (into [:<>])))

(defn label-filter-actions [id]
  [:button.btn.btn-outline-primary
   {:title "Add a new disjunction filter condition"
    :on-click (u/link-evt-handler [:labels/add-disjunction id])}
   [:span.me-1 [co/icon :plus-square]] "Add Filter"])

(defn render-filter-editor [id lf]
  (->> (concat
        [[:h6 "Label Filters"]]
        (if (empty? lf)
          [[:p [:em "Applies to all repositories."]]]
          (map-indexed (partial edit-label-filter-disjunction id) lf))
        [[label-filter-actions id]])
       (into [:div])))

(defn edit-label-filters
  "Renders editor for label filters.  These consist of zero or more disjunctions 
   (logical `OR`), and each disjunction can consist of one or more conjunctions
   (logical `AND`).  The id is used to store information in the local db."
  [editor-id]
  (let [lbl (rf/subscribe [:labels/edit editor-id])]
    [render-filter-editor editor-id @lbl]))

(defn label-filters-desc [lf]
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
