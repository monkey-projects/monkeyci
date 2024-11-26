(ns monkey.ci.gui.apis.bitbucket
  "Functions for invoking the bitbucket api"
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.apis.common :as c]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def api-version "2.0")

(defn api-url [path]
  (str "https://api.bitbucket.org/" api-version path))

(defn api-request [db {:keys [path] :as opts}]
  (cond-> (c/api-request (-> opts
                             (update :token #(or % (ldb/bitbucket-token db)))
                             (dissoc :path)))
    path (assoc :uri (api-url path))))

(def repos ::repos)

(defn set-repos [db r]
  (assoc db repos r))

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(u/db-sub :bitbucket/alerts alerts)
(u/db-sub :bitbucket/repos repos)

(rf/reg-event-fx
 :bitbucket/load-repos
 (fn [{:keys [db]} _]
   {:dispatch [::load-workspaces]
    :db (set-repos db nil)}))

(rf/reg-event-fx
 ::load-workspaces
 (fn [{:keys [db]} _]
   {:http-xhrio (api-request
                 db
                 {:path "/user/permissions/workspaces"
                  :method :get
                  :on-success [::load-workspaces--success]
                  :on-failure [::load-workspaces--failed]})}))

(rf/reg-event-fx
 ::load-workspaces--success
 (fn [_ [_ res]]
   ;; For each workspace, retrieve the repositories
   ;; TODO Pagination
   {:dispatch-n (->> res :values
                     (map :workspace)
                     (map :uuid)
                     (mapv (partial vector ::load-repos)))}))

(rf/reg-event-fx
 ::load-workspaces--failed
 (u/req-error-handler-db
  (fn [{:keys [db]} [_ err]]
    (set-alerts db [(a/bitbucket-ws-failed err)]))))

(rf/reg-event-fx
 ::load-repos
 (fn [{:keys [db]} [_ workspace-id]]
   {:http-xhrio (api-request
                 db
                 {:path (str "/repositories/" workspace-id)
                  :method :get
                  :on-success [::load-repos--success workspace-id]
                  :on-failure [::load-repos--failed workspace-id]})}))

(rf/reg-event-db
 ::load-repos--success
 (fn [db [_ _ body]]
   ;; TODO Pagination
   (update db repos (comp vec (fnil concat [])) (:values body))))

(rf/reg-event-fx
 ::load-repos--failed
 (fn [ctx [evt _ err]]
   ((u/req-error-handler-db
     (fn [{:keys [db]} [_ err]]
       (set-alerts db [(a/bitbucket-repos-failed err)])))
    ctx [evt err])))
