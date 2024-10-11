(ns monkey.ci.gui.repo.events
  (:require [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.repo.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.server-events]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def stream-id ::event-stream)

(rf/reg-event-fx
 :repo/init
 (fn [{:keys [db]} _]
   (lo/on-initialize
    db db/id
    {:init-events         [[:repo/load (r/customer-id db)]]
     :leave-event         [:repo/leave]
     :event-handler-event [:repo/handle-event]})))

(rf/reg-event-fx
 :repo/leave
 (fn [{:keys [db]} _]
   (-> (lo/on-leave db db/id)
       ;; Also clear loaded state
       (update :db lo/clear-all db/id))))

(rf/reg-event-fx
 :repo/load
 (fn [{:keys [db]} [_ cust-id]]
   (let [existing (cdb/customer db)]
     (cond-> {:db (db/set-builds db nil)}
       (not existing)
       (assoc :dispatch [:customer/load cust-id])))))

(rf/reg-event-fx
 :builds/load
 (lo/loader-evt-handler
  db/id
  (fn [_ {:keys [db]} _]
    (let [params (get-in db [:route/current :parameters :path])]
      [:secure-request
       :get-builds
       (select-keys params [:customer-id :repo-id])
       [:builds/load--success]
       [:builds/load--failed]]))))

(rf/reg-event-fx
 :builds/reload
 (fn [{:keys [db]} _]
   {:dispatch [:builds/load]
    :db (lo/reset-loaded db db/id)}))

(rf/reg-event-db
 :builds/load--success
 (fn [db [_ resp]]
   (lo/on-success db db/id resp)))

(rf/reg-event-db
 :builds/load--failed
 (fn [db [_ err]]
   (lo/on-failure db db/id "Could not load builds: " err)))

(def should-handle-evt? #{:build/start :build/pending :build/updated})

(defn handle-event [db evt]
  (cond-> db
    (should-handle-evt? (:type evt)) (db/update-build (:build evt))))

(defn- for-repo? [db evt]
  (let [get-id (juxt :customer-id :repo-id)]
    (= (get-id (:build evt))
       (-> (r/current db)
           (r/path-params)
           (get-id)))))

(rf/reg-event-db
 :repo/handle-event
 (fn [db [_ evt]]
   (when (for-repo? db evt)
     (handle-event db evt))))

(rf/reg-event-db
 :repo/show-trigger-build
 (fn [db _]
   (db/set-show-trigger-form db true)))

(rf/reg-event-db
 :repo/hide-trigger-build
 (fn [db _]
   (db/set-show-trigger-form db nil)))

(defn- add-ref
  "Adds query params according to the trigger form input"
  [params {:keys [trigger-type trigger-ref]}]
  (let [tt (first trigger-type)
        v (first trigger-ref)]
    (cond-> params
      (and tt v) (assoc (keyword tt) v))))

(rf/reg-event-fx
 :repo/trigger-build
 (fn [{:keys [db]} [_ form-vals]]
   (let [params (get-in db [:route/current :parameters :path])]
     {:db (-> db
              (db/set-triggering)
              (db/reset-alerts))
      :dispatch [:secure-request
                 :trigger-build
                 (-> (select-keys params [:customer-id :repo-id])
                     (add-ref form-vals))
                 [:repo/trigger-build--success]
                 [:repo/trigger-build--failed]]})))

(rf/reg-event-db
 :repo/trigger-build--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (db/set-alerts [{:type :info
                        :message (str "Build " (:build-id body) " started.")}])
       (db/set-show-trigger-form nil))))

(rf/reg-event-db
 :repo/trigger-build--failed
 (fn [db [_ err]]
   (db/set-alerts db [{:type :danger
                       :message (str "Could not start build: " (u/error-msg err))}])))

(rf/reg-event-fx
 :repo/load+edit
 (fn [{:keys [db]} _]
   (let [cust-id (r/customer-id db)]
     {:dispatch [:secure-request
                 :get-customer
                 {:customer-id cust-id}
                 [:repo/load+edit--success]
                 [:repo/load+edit--failed]]
      :db (-> db
              (db/reset-edit-alerts)
              (db/unmark-saving))})))

(rf/reg-event-fx
 :repo/load+edit--success
 (fn [{:keys [db]} [_ resp]]
   (let [repo-id (r/repo-id db)]
     {:dispatch [:customer/load--success resp]
      :db (db/set-editing db (->> (:body resp)
                                  :repos
                                  (filter (comp (partial = repo-id) :id))
                                  (first)))})))

(rf/reg-event-db
 :repo/load+edit--failed
 (fn [db [_ err]]
   (db/set-edit-alerts db [{:type :danger
                            :message (str "Unable to fetch repository info: " (u/error-msg err))}])))

(rf/reg-event-db
 :repo/name-changed
 (fn [db [_ v]]
   (assoc-in db [db/editing :name] v)))

(rf/reg-event-db
 :repo/main-branch-changed
 (fn [db [_ v]]
   (assoc-in db [db/editing :main-branch] v)))

(rf/reg-event-db
 :repo/url-changed
 (fn [db [_ v]]
   (assoc-in db [db/editing :url] v)))

(rf/reg-event-db
 :repo/label-add
 (fn [db _]
   (db/update-labels db (fnil conj []) {:name "New label" :value "New value"})))

(rf/reg-event-db
 :repo/label-removed
 (fn [db [_ lbl]]
   (db/update-labels db (partial filterv (partial not= lbl)))))

(rf/reg-event-db
 :repo/label-name-changed
 (fn [db [_ lbl new-name]]
   (db/update-label db lbl assoc :name new-name)))

(rf/reg-event-db
 :repo/label-value-changed
 (fn [db [_ lbl new-name]]
   (db/update-label db lbl assoc :value new-name)))

(rf/reg-event-fx
 :repo/save
 (fn [{:keys [db]} _]
   (let [params (-> (r/current db)
                    (r/path-params)
                    (select-keys [:customer-id :repo-id]))]
     {:dispatch [:secure-request
                 :update-repo
                 (-> params
                     (assoc :repo (-> (db/editing db)
                                      (assoc :id (:repo-id params)
                                             :customer-id (:customer-id params)))))
                 [:repo/save--success]
                 [:repo/save--failed]]
      :db (-> db
              (db/mark-saving)
              (db/reset-edit-alerts))})))

(rf/reg-event-db
 :repo/save--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (cdb/replace-repo body)
       (db/unmark-saving)
       (db/set-edit-alerts [{:type :success
                             :message "Repository changes have been saved."}]))))

(rf/reg-event-db
 :repo/save--failed
 (fn [db [_ err]]
   (-> db
       (db/set-edit-alerts [{:type :danger
                             :message (str "Failed to save changes: " (u/error-msg err))}])
       (db/unmark-saving))))

(rf/reg-event-fx
 :repo/delete
 (fn [{:keys [db]} _]
   {:dispatch [:secure-request
               :delete-repo
               (r/path-params (r/current db))
               [:repo/delete--success]
               [:repo/delete--failed]]
    :db (db/mark-deleting db)}))

(defn- remove-repo [cust repo-id]
  (update cust :repos (partial remove (comp (partial = repo-id) :id))))

(rf/reg-event-fx
 :repo/delete--success
 (fn [{:keys [db]} _]
   (let [params (-> (r/current db) (r/path-params))
         repo-id (:repo-id params)
         repo (u/find-by-id repo-id (:repos (cdb/get-customer db)))]
     {:db (-> db
              (db/unmark-deleting)
              (cdb/update-customer remove-repo repo-id)
              (cdb/set-alerts
               [{:type :info
                 :message (str "Repository " (:name repo) " has been deleted.")}]))
      :dispatch [:route/goto :page/customer (select-keys params [:customer-id])]})))

(rf/reg-event-db
 :repo/delete--failed
 (fn [db [_ err]]
   (-> db
       (db/unmark-deleting)
       (db/set-edit-alerts
        [{:type :danger
          :message (str "Unable to delete repository: " (u/error-msg err))}]))))
