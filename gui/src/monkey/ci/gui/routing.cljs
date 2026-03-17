(ns monkey.ci.gui.routing
  "Routing functionality, used to load the appropriate page according to browser path.
   The central part is the router, which is kept in state and referred to by various
   functions and event handlers."
  (:require [monkey.ci.gui.logging :as log]
            [re-frame.core :as rf]
            [reitit.frontend :as f]
            [reitit.frontend.easy :as rfe]))

(def current
  "Retrieve current route from app db"
  :route/current)

(def route-name
  "Retrieves name of given route"
  (comp :name :data))

(def path-params (comp :path :parameters))

(defn set-current [db r]
  (assoc db current r))

(def org-id
  "Retrieve current org id from app db"
  (comp :org-id path-params current))

(def repo-id
  "Retrieve current repo id from app db"
  (comp :repo-id path-params current))

(rf/reg-sub
 :route/current
 (fn [db _]
   (current db)))

(rf/reg-sub
 :route/org-id
 :<- [:route/current]
 (fn [c _]
   (get-in c [:parameters :path :org-id])))

(def on-page-leave ::on-page-leave)

(defn- path-changed? [from to]
  (not= (:path from) (:path to)))

(rf/reg-event-fx
 :route/changed
 (fn [{:keys [db]} [_ match]]
   (when (path-changed? (current db) match)
     (log/debug "Changing current route from" (clj->js (current db)) "into" (clj->js match))
     (let [handlers (on-page-leave db)]
       (cond-> {:db (-> db
                        (assoc current match)
                        (dissoc on-page-leave))}
         (not-empty handlers) (assoc :dispatch-n handlers))))))

(def make-router f/router)

(defonce router (atom nil))

(defn on-route-change [match _]
  (log/debug "Route changed:" match)
  (rf/dispatch [:route/changed match]))

(defn start-router [r]
  (reset! router r)
  (rfe/start! r on-route-change {:use-fragment false}))

(def public?
  "Checks if given route is publicly accessible"
  (comp true? :public? :data))

(defn match-by-name [r params]
  ;; There is a bug in reitit where parameters are not filled in (no coercion)
  ;; when calling `match-by-name` so we do a second lookup using the path,
  ;; which seems to coerce correctly.
  (->> (f/match-by-name @router r params)
       :path
       (f/match-by-path @router)))

(defn path-for [id & [params]]
  (some-> (match-by-name id params)
          :path)
  ;; Only works after start!
  #_(rfe/href id params))

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
  (.pushState js/history (clj->js {}) nil (str (origin) p)))

(rf/reg-fx
 :route/goto
 ;; Changes browser path
 (fn [p]
   (set-path! p)))

(rf/reg-event-fx
 :route/goto
 (fn [_ [_ r params]]
   (let [m (match-by-name r params)]
     {:route/goto (:path m)
      :dispatch [:route/changed m]})))

(rf/reg-event-fx
 :route/goto-path
 (fn [_ [_ path]]
   (when-let [m (f/match-by-path @router path)]
     {:route/goto path
      :dispatch [:route/changed m]})))

(rf/reg-event-db
 :route/on-page-leave
 (fn [db [_ evt]]
   (update db on-page-leave (comp vec conj) evt)))

