(ns monkey.ci.gui.routing
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

(defonce main-router
  ;; Instead of pointing to the views directly, we refer to a keyword, which
  ;; is linked in another namespace (pages) to the actual view.  This allows
  ;; us to refer to the routing namespace from views, e.g. to resolve paths
  ;; by route names.
  (f/router
   [["/" :page/root]
    ["/login" :page/login]
    ["/o/join" {:conflicting true
                :name :page/org-join}]
    ["/o/new" {:conflicting true
               :name :page/org-new}]
    ["/o/:org-id" {:conflicting true
                   :name :page/org}]
    ["/o/:org-id/add-repo" :page/add-repo]
    ["/o/:org-id/add-repo/github" :page/add-github-repo]
    ["/o/:org-id/add-repo/bitbucket" :page/add-bitbucket-repo]
    ["/o/:org-id/settings" :page/org-settings]
    ["/o/:org-id/params" :page/org-params]
    ["/o/:org-id/ssh-keys" :page/org-ssh-keys]
    ["/o/:org-id/r/:repo-id" :page/repo]
    ["/o/:org-id/r/:repo-id/edit" :page/repo-edit]
    ["/o/:org-id/r/:repo-id/settings" :page/repo-settings]
    ["/o/:org-id/r/:repo-id/webhooks" :page/webhooks]
    ["/o/:org-id/r/:repo-id/b/:build-id" :page/build]
    ["/o/:org-id/r/:repo-id/b/:build-id/j/:job-id" :page/job]
    ["/github/callback" :page/github-callback]
    ["/bitbucket/callback" :page/bitbucket-callback]]))

(defonce admin-router
  (f/router
   [["/" :admin/root]
    ["/login" :admin/login]
    ["/credits" :admin/credits]
    ["/credits/:org-id" :admin/org-credits]
    ["/builds/clean" :admin/clean-builds]
    ["/forget" :admin/forget-users]
    ["/invoicing" :admin/invoicing]
    ["/invoicing/:org-id" :admin/org-invoices]]))

(defonce router (atom main-router))

(def public?
  "Route names that are publicly accessible."
  #{:page/login :page/github-callback :page/bitbucket-callback})

(defn on-route-change [match _]
  (log/debug "Route changed:" match)
  (rf/dispatch [:route/changed match]))

(defn- start-router []
  (rfe/start! @router on-route-change {:use-fragment false}))

(defn start! []
  (reset! router main-router)
  (start-router))

(defn start-admin! []
  (reset! router admin-router)
  (start-router))

(defn path-for [id & [params]]
  (some-> (f/match-by-name @router id params)
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

(defn- match-by-name [r params]
  ;; There is a bug in reitit where parameters are not filled in (no coercion)
  ;; when calling `match-by-name` so we do a second lookup using the path,
  ;; which seems to coerce correctly.
  (->> (f/match-by-name @router r params)
       :path
       (f/match-by-path @router)))

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

