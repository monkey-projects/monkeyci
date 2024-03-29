(ns monkey.ci.gui.components
  (:require [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn logo []
  [:img.img-fluid.rounded {:src "/img/monkeyci-large.png" :title "Placeholder Logo"}])

(defn render-alert [{:keys [type message]}]
  [:div {:class (str "alert alert-" (name type))} message])

(defn alerts [id]
  (let [s (rf/subscribe id)]
    (when (not-empty @s)
      (->> @s
           (map render-alert)
           (into [:<>])))))

(defn icon [n]
  [:i {:class (str "bi bi-" (name n))}])

(defn icon-btn [i lbl evt & [opts]]
  [:button.btn.btn-primary (merge {:on-click #(rf/dispatch evt)} opts) [:span [icon i] " " lbl]])

(defn reload-btn [evt & [opts]]
  (icon-btn :arrow-clockwise "Reload" evt opts))

(defn breadcrumb [parts]
  [:nav {:aria-label "breadcrumb"}
   (->> (loop [p parts
               r []]
          (let [last? (empty? (rest p))
                v (first p)
                n (if last?
                    [:li.breadcrumb-item.active {:aria-current "page"}
                     (:name v)]
                    [:li.breadcrumb-item 
                     [:a {:href (:url v)} (:name v)]])
                r+ (conj r n)]
            (if last?
              r+
              (recur (rest p) r+))))
        (into [:ol.breadcrumb]))])

(defn build-result [r]
  (let [type (condp = (keyword r)
               :error :text-bg-danger
               :failure :text-bg-danger
               :success :text-bg-success
               :text-bg-secondary)]
    [:span {:class (str "badge " (name type))} (or r "running")]))

(defn- item-id [id idx]
  (str (name id) "-" idx))

(defn- accordion-item [id idx {:keys [title contents collapsed]}]
  (let [iid (item-id id idx)] 
    [:div.accordion-item
     [:h2.accordion-header
      [:button.accordion-button (cond-> {:type :button
                                         :data-bs-toggle :collapse
                                         :data-bs-target (u/->dom-id iid)
                                         :aria-expanded true
                                         :aria-controls iid}
                                  collapsed (assoc :class :collapsed
                                                   :aria-expanded false))
       title]]
     [:div.accordion-collapse.collapse (cond-> {:id iid
                                                :data-bs-parent (u/->dom-id id)}
                                         (not collapsed) (assoc :class :show))
      [:div.accordion-body
       contents]]]))

(defn accordion
  "Accordion component.  This uses the Bootstrap Javascript for functionality."
  [id items]
  (->> (map-indexed (partial accordion-item id) items)
       (into [:div.accordion {:id id}])))

(defn modal [id title contents]
  [:div.modal.fade.modal-lg
   {:id id
    :tab-iindex -1}
   [:div.modal-dialog
    [:div.modal-content
     [:div.modal-header
      [:div.modal-title title]
      [:button.btn-close {:type :button
                          :data-bs-dismiss "modal"
                          :aria-label "Close"}]]
     [:div.modal-body
      contents]
     [:div.modal-footer
      [:button.btn.btn-secondary {:type :button
                                  :data-bs-dismiss "modal"}
       "Close"]]]]])
