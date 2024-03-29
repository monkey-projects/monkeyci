(ns monkey.ci.gui.routing
  (:require [monkey.ci.gui.logging :as log]
            [re-frame.core :as rf]
            [reitit.frontend :as f]
            [reitit.frontend.easy :as rfe]))

(def current
  "Retrieve current route from app db"
  :route/current)

(def customer-id
  "Retrieve current customer id from app db"
  (comp :customer-id :path :parameters current))

(rf/reg-sub
 :route/current
 (fn [db _]
   (current db)))

(def on-page-leave ::on-page-leave)

(rf/reg-event-fx
 :route/changed
 (fn [{:keys [db]} [_ match]]
   (log/debug "Changing current route from" (current db) "into" match)
   (let [handlers (on-page-leave db)]
     (log/debug "Found" (count handlers) "leave handlers")
     (cond-> {:db (-> db
                      (assoc current match)
                      (dissoc on-page-leave))}
       (not-empty handlers) (assoc :dispatch-n handlers)))))

(defonce router
  ;; Instead of pointing to the views directly, we refer to a keyword, which
  ;; is linked in another namespace (pages) to the actual view.  This allows
  ;; us to refer to the routing namespace from views, e.g. to resolve paths
  ;; by route names.
  (f/router
   [["/" :page/root]
    ["/login" :page/login]
    ["/c/:customer-id" :page/customer]
    ["/c/:customer-id/r/:repo-id" :page/repo]
    ["/c/:customer-id/r/:repo-id/b/:build-id" :page/build]
    ["/github/callback" :page/github-callback]]))

(defn on-route-change [match history]
  (log/debug "Route changed:" match)
  (rf/dispatch [:route/changed match]))

(defn start! []
  (rfe/start! router on-route-change {:use-fragment false}))

(defn path-for [id & [params]]
  (some-> (f/match-by-name router id params)
          :path))

(def path-params (comp :path :parameters))

(defn origin
  "Retrieves the origin of the current location"
  []
  (.-origin js/location))

(defn uri-encode
  "URI-encodes the given string so it can be passed as a query parameter"
  [s]
  (js/encodeURIComponent s))

(defn set-path!
  "Sets the current browser path without reloading the page"
  [p]
  #_(set! (.-pathname js/location) p)
  (.pushState js/history (clj->js {}) nil (str (origin) p)))

(rf/reg-fx
 :route/goto
 (fn [p]
   (set-path! p)))

(rf/reg-event-fx
 :route/goto
 (fn [_ [_ r & [params]]]
   (let [p (apply path-for r params)
         m (f/match-by-name router r params)]
     {:route/goto p
      :dispatch [:route/changed m]})))

(rf/reg-event-db
 :route/on-page-leave
 (fn [db [_ evt]]
   (update db on-page-leave (comp vec conj) evt)))
