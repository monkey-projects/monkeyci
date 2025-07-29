(ns monkey.ci.gui.components
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.template :as templ]
            [monkey.ci.gui.time :as t]
            [monkey.ci.gui.subs]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]
            #?@(:node [] ; Exclude ansi_up when building for node
                :cljs [["ansi_up" :refer [AnsiUp]]
                       [reagent.core :as rc]])))

(defn logo []
  (templ/logo))

(def render-alert a/render-alert)
(def alerts a/component)

(defn user-avatar [{:keys [avatar-url] :as u}]
  (when avatar-url
    [:span.avatar.avatar-sm.avatar-circle
     [:img.avatar-img {:src avatar-url :alt "Avatar"}]]))

(defn icon [n]
  [:i {:class (str "bi bi-" (name n))}])

(defn icon-text
  "Displays text with an icon prefix"
  [i & txt]
  (into [:<> [:span.me-2 [icon i]]] txt))

(defn icon-btn [i lbl evt & [opts]]
  [:button.btn.btn-primary
   (merge {:on-click (u/link-evt-handler evt)} opts) [:span.me-2 [icon i]] lbl])

(defn icon-btn-sm [i evt & [opts]]
  [:button.btn.btn-primary.btn-icon.btn-sm
   (merge {:on-click (u/link-evt-handler evt)} opts) [icon i]])

(def overview-icon
  [icon :house])

(def org-icon
  [icon :people-fill])

(def repo-icon
  [icon :box-seam])

(def repo-group-icon
  [icon :boxes])

(def build-icon
  [icon :wrench])

(def delete-icon
  [icon :trash])

(def cancel-icon
  [icon :x-square])

(def search-icon
  [icon :search])

(def sort-up-icon
  [icon :caret-up-fill])

(def sort-down-icon
  [icon :caret-down-fill])

(defn reload-btn [evt & [opts]]
  (icon-btn :arrow-clockwise "Reload" evt opts))

(defn reload-btn-sm
  "Small reload button, icon only"
  [evt & [opts]]
  [:button.btn.btn-outline-primary.btn-icon.btn-sm
   (merge {:on-click (u/link-evt-handler evt)
           :title "Reload"}
          opts)
   [icon :arrow-clockwise]])

(defn cancel-btn
  "Generic cancel button.  Posts given event when pressed."
  [evt & [lbl]]
  [:button.btn.btn-outline-danger
   {:on-click (u/link-evt-handler evt)}
   [:span.me-2 cancel-icon] (or lbl "Cancel")])

(defn close-btn
  "Generic close button.  Posts given event when pressed."
  [evt]
  [cancel-btn evt "Close"])

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
                     :error        [:text-danger :exclamation-circle]
                     :failure      [:text-danger :exclamation-circle]
                     :running      [:text-info :play-circle]
                     :pending      [:text-warning :pause-circle]
                     :initializing [:text-warning :play-circle]
                     :canceled     [:text-warning :x-circle]}
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
       (rc/unsafe-html (.ansi_to_html ansi-up l)))))

(defn ->html
  "Converts raw string to html"
  [l]
  (if (string? l)
    [:span
     {:dangerouslySetInnerHTML (ansi->html l)}]
    l))

(defn colored [s color]
  (str "\033[" color "m" s "\033[0m"))

(defn log-viewer [contents]
  (into [:div.bg-dark.text-white.font-monospace.overflow-auto.text-nowrap.p-1
         {:style {:min-height "20em"}}]
        contents))

(defn log-contents [raw]
  (->> raw
       (map ->html)
       (log-viewer)))

(defn build-elapsed [b]
  (let [e (u/build-elapsed b)]
    (when (pos? e)
      (t/format-seconds (int (/ e 1000))))))

(defn filter-input [opts]
  [:div.input-group.input-group-merge
   [:span.input-group-prepend.input-group-text search-icon]
   [:input.form-control opts]])

(defn tab-header [i lbl]
  [:span [:span.me-2 [icon i]] lbl])

(defn docs-link [page title]
  [:a {:href (templ/docs-url (str "/" page)) :target :_blank} title])

(defn bg-shape []
  [:div.shape-container
   [:div.shape.shape-bottom.zi-1
   [:svg {:view-box "0 0 3000 1000"
          :fill "none"
          :xmlns "http://www.w3.org/2000/svg"}
    [:path {:d "M0 1000V583.723L3000 0V1000H0Z" :fill "#fff"}]]]])

(defn page-title [& contents]
  (into [:h3.text-primary] contents))
