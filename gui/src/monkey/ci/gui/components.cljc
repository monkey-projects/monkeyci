(ns monkey.ci.gui.components
  (:require [monkey.ci.gui.time :as t]
            [monkey.ci.gui.subs]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]
            #?@(:node [] ; Exclude ansi_up when building for node
                :cljs [["ansi_up" :refer [AnsiUp]]])))

(defn logo []
  [:img.img-fluid.rounded {:src "/img/monkeyci-bw-small.png" :title "Placeholder Logo"}])

(defn render-alert [{:keys [type message]}]
  [:div {:class (str "alert alert-" (name type))} message])

(defn alerts [id]
  (let [s (rf/subscribe id)]
    (when (not-empty @s)
      (->> @s
           (map render-alert)
           (into [:<>])))))

(defn user-avatar [{:keys [avatar-url]}]
  (when avatar-url
    [:img.img-thumbnail.img-fluid {:width "50px" :src avatar-url :alt "Avatar"}]))

(defn icon [n]
  [:i {:class (str "bi bi-" (name n))}])

(defn icon-btn [i lbl evt & [opts]]
  [:button.btn.btn-primary (merge {:on-click (u/link-evt-handler evt)} opts) [:span.me-2 [icon i]] lbl])

(defn icon-btn-sm [i evt & [opts]]
  [:button.btn.btn-primary.btn-icon.btn-sm
   (merge {:on-click (u/link-evt-handler evt)} opts) [icon i]])

(defn reload-btn [evt & [opts]]
  (icon-btn :arrow-clockwise "Reload" evt opts))

(defn reload-btn-sm
  "Small reload button, icon only"
  [evt & [opts]]
  (icon-btn-sm :arrow-clockwise evt (assoc opts :title "Reload")))

(defn cancel-btn
  "Generic cancel button.  Posts given event when pressed."
  [evt]
  [:button.btn.btn-outline-danger
   {:on-click (u/link-evt-handler evt)}
   [:span.me-2 [icon :x-square]] "Cancel"])

(defn build-result [r]
  (let [r (or r "running")
        type (condp = (keyword r)
               :error :bg-danger
               :failure :bg-danger
               :success :bg-success
               :initializing :bg-warning
               :running :bg-info
               :canceled :bg-warning
               :skipped :bg-warning
               :bg-secondary)]
    [:span {:class (str "badge " (name type))} r]))

(defn status-icon [status size]
  (let [[cl i] (get {:success      [:text-success :check-circle]
                     :error        [:text-danger :x-circle]
                     :running      [:text-info :play-circle]
                     :pending      [:text-warning :pause-circle]
                     :initializing [:text-warning :play-circle]
                     :canceled     [:text-warning :x-circle]
}
                    status
                    [:text-default :question-circle])]
    [:div (cond-> {:style {:font-size size}
                   :class cl}
            status (assoc :title (name status)))
     [icon i]]))

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
    :tab-index -1}
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

(defn date-time
  "Reformats given object as a date-time"
  [x]
  (when x
    (t/format-datetime (t/parse x))))

;; Node does not support ESM modules, so we need to apply this workaround when testing
#?(:node
   (defn ansi->html [l]
     l)
   :cljs
   (do
     (def ansi-up (AnsiUp.))
     (defn- ansi->html [l]
       (.ansi_to_html ansi-up l))))

(defn ->html
  "Converts raw string to html"
  [l]
  (if (string? l)
    [:span
     {:dangerouslySetInnerHTML {:__html (ansi->html l)}}]
    l))

(defn log-contents [raw]
  (->> raw
       (map ->html)
       (into [:p.bg-dark.text-white.font-monospace.overflow-auto.text-nowrap.p-1
              {:style {:min-height "20em"}}])))

(defn build-elapsed [b]
  (let [e (u/build-elapsed b)]
    (when (pos? e)
      (t/format-seconds (int (/ e 1000))))))
