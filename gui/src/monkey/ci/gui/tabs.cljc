(ns monkey.ci.gui.tabs
  "Renders bootstrap tab pages"
  (:require [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def header-id
  "Determine header id"
  (some-fn :id :header))

(defn- tab-header [id curr {:keys [header] :as h}]
  (let [header-id (header-id h)]
    [:li.nav-item
     [:a.nav-link (cond-> {:href ""
                           :on-click (u/link-evt-handler [:tab/tab-changed id header-id])}
                    (= curr header-id) (assoc :class :active
                                              :aria-current :page))
      header]]))

(rf/reg-sub
 :tab/current
 (fn [db [_ id]]
   (get-in db [::tabs id :current])))

(rf/reg-event-db
 :tab/tab-changed
 (fn [db [_ id changed]]
   (log/debug "Tab changed:" (str id) "to" (str changed))
   (assoc-in db [::tabs id :current] changed)))

(defn tabs
  "Controlled tabs component.  The headers are a list of tab configs that
   have a `:header` and `:contents`.  Current tab is indicated by `:current?`."
  [id headers]
  (let [curr (rf/subscribe [:tab/current id])
        by-id (group-by header-id headers)]
    (when-not @curr
      (rf/dispatch [:tab/tab-changed id (->> headers (filter :current?) first header-id)]))
    [:<>
     (->> headers
          (map (partial tab-header id @curr))
          (into [:ul.nav.nav-tabs.mb-2]))
     (some->> @curr (get by-id) first :contents)]))
