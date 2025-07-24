(ns monkey.ci.gui.tabs
  "Renders bootstrap tab pages"
  (:require [monkey.ci.gui.routing :as r]
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
   (assoc-in db [::tabs id :current] changed)))

(defn- current-or-first [headers]
  (or (->> headers (filter :current?) first)
      (first headers)))

(defn tabs-labels
  "Renders tab labels, without the contents"
  [id headers opts]
  (let [curr (rf/subscribe [:tab/current id])]
    (->> headers
         (map (partial tab-header id @curr))
         (into [:ul.nav.nav-tabs.mb-2 opts]))))

(defn tabs-contents
  [id headers]
  (let [curr (rf/subscribe [:tab/current id])
        by-id (group-by header-id headers)]
    (some->> @curr (get by-id) first :contents)))

(defn tabs
  "Controlled tabs component.  The headers are a list of tab configs that
   have a `:header` and `:contents`.  Current tab is indicated by `:current?`."
  [id headers & [opts]]
  (let [curr (rf/subscribe [:tab/current id])]
    (when-not @curr
      (rf/dispatch-sync [:tab/tab-changed id (header-id (current-or-first headers))]))
    [:<>
     [tabs-labels id headers opts]
     [tabs-contents id headers]]))

(defn- render-header [active path {:keys [id header link]}]
  [:li.nav-item
   [:a.nav-link (cond-> {:href (r/path-for link path)}
                  (= id active) (assoc :class :active))
    header]])

(defn settings-tabs
  "Renders vertical tabs, used for setting pages.  Each tab pages has an id,
   a header and an associated link.  The current route parameters are passed
   to the link."
  [tabs active]
  (let [r (rf/subscribe [:route/current])]
    (->> tabs
         (map (partial render-header active (r/path-params @r)))
         (into [:div.nav.nav-tabs.nav-vertical.nav-link-gray]))))
