(ns monkey.ci.gui.tabs
  "Renders bootstrap tab pages"
  (:require [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn- tab-header [id curr {:keys [header] :as h}]
  [:li.nav-item
   [:a.nav-link (cond-> {:href ""
                         :on-click (u/link-evt-handler [:tab/tab-changed id h])}
                  (= curr h) (assoc :class :active
                                    :aria-current :page))
    header]])

(rf/reg-sub
 :tab/current
 (fn [db [_ id]]
   (get-in db [::tabs id :current])))

(rf/reg-event-db
 :tab/tab-changed
 (fn [db [_ id changed]]
   (assoc-in db [::tabs id :current] changed)))

(defn tabs [id headers]
  (log/info "Rendering tabs:" id)
  (let [curr (rf/subscribe [:tab/current id])]
    (when-not @curr
      (rf/dispatch [:tab/tab-changed id (->> headers (filter :current?) first)]))
    [:<>
     (->> headers
          (map (partial tab-header id @curr))
          (into [:ul.nav.nav-tabs.mb-2]))
     (when @curr
       (:contents @curr))]))
