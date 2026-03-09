(ns monkey.ci.gui.repo.events
  (:require [medley.core :as mc]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.apis.github]
            [monkey.ci.gui.org.db :as odb]
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
    {:init-events         [[:repo/load (r/org-id db)]]
     :leave-event         [:repo/leave]
     :event-handler-event [:repo/handle-event]})))

(rf/reg-event-fx
 :repo/leave
 (fn [{:keys [db]} _]
   (-> (lo/on-leave db db/id)
       ;; Also clear loaded state to avoid builds showing up in the wrong repo
       (update :db lo/clear-all db/id))))

(rf/reg-event-fx
 :repo/load
 ;; Since repos are part of the org, this actually loads the org.
 (fn [{:keys [db]} [_ org-id]]
   (let [existing (odb/org db)]
     (cond-> {:db (db/set-builds db nil)}
       (not existing)
       (assoc :dispatch [:org/load org-id])))))

(rf/reg-event-fx
 :builds/load
 (lo/loader-evt-handler
  db/id
  (fn [_ {:keys [db]} _]
    (let [params (get-in db [:route/current :parameters :path])]
      [:secure-request
       :get-builds
       (select-keys params [:org-id :repo-id])
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
   (lo/on-failure db db/id a/builds-load-failed err)))

(def should-handle-evt? #{:build/start :build/pending :build/updated})

(defn handle-event [db evt]
  (cond-> db
    (should-handle-evt? (:type evt)) (db/update-build (:build evt))))

(defn- for-repo? [db evt]
  (let [get-id (juxt :org-id :repo-id)]
    (= (get-id (:build evt))
       (-> (r/current db)
           (r/path-params)
           (get-id)))))

(rf/reg-event-db
 :repo/handle-event
 (fn [db [_ evt]]
   (when (for-repo? db evt)
     (handle-event db evt))))

(defn- set-default-trigger-ref [f db]
  (let [b (some-> (db/find-repo-in-org (odb/get-org db) (r/repo-id db))
                  :main-branch)]
    (cond-> f
      b (assoc :trigger-type "branch"
               :trigger-ref b))))

(rf/reg-event-db
 :repo/show-trigger-build
 (fn [db _]
   (-> db
       (db/set-show-trigger-form true)
       (db/update-trigger-form
        (fn [{:keys [params] :as f}]
          (cond-> f
            (empty? f) (set-default-trigger-ref db)
            ;; Add a single empty param for input
            (empty? params) (assoc :params [{:name "" :value ""}])))))))

(rf/reg-event-db
 :repo/hide-trigger-build
 (fn [db _]
   (db/set-show-trigger-form db nil)))

(rf/reg-event-db
 :repo/trigger-type-changed
 (fn [db [_ v]]
   (db/update-trigger-form db assoc :trigger-type v)))

(rf/reg-event-db
 :repo/trigger-ref-changed
 (fn [db [_ v]]
   (db/update-trigger-form db assoc :trigger-ref v)))

(rf/reg-event-db
 :repo/add-trigger-param
 (fn [db _]
   (db/update-trigger-form db update :params conj {:name "" :value ""})))

(rf/reg-event-db
 :repo/remove-trigger-param
 (fn [db [_ idx]]
   (db/update-trigger-form db update :params (comp vec (partial mc/remove-nth idx)))))

(rf/reg-event-db
 :repo/trigger-param-name-changed
 (fn [db [_ idx v]]
   (db/update-trigger-param db idx assoc :name v)))

(rf/reg-event-db
 :repo/trigger-param-val-changed
 (fn [db [_ idx v]]
   (db/update-trigger-param db idx assoc :value v)))

(defn- add-ref
  "Adds query params according to the trigger form input"
  [params {tt :trigger-type v :trigger-ref}]
  (cond-> params
    (and tt v) (assoc (keyword tt) v)))

(defn- add-build-params
  "Adds non-empty build parameters to the request body"
  [r {:keys [params]}]
  (let [->e (juxt :name :value)
        e? (partial every? empty?)]
    (cond-> r
      (not-empty params)
      (assoc :params (->> params
                          (map ->e)
                          (remove e?)
                          (into {}))))))

(rf/reg-event-fx
 :repo/trigger-build
 (fn [{:keys [db]} _]
   (let [params (get-in db [:route/current :parameters :path])
         f (db/trigger-form db)]
     {:db (-> db
              (db/set-triggering)
              (db/reset-alerts))
      :dispatch [:secure-request
                 :trigger-build
                 (-> (select-keys params [:org-id :repo-id])
                     (add-ref f)
                     (add-build-params f))
                 [:repo/trigger-build--success]
                 [:repo/trigger-build--failed]]})))

(rf/reg-event-db
 :repo/trigger-build--success
 (fn [db [_ {:keys [body]}]]
   (-> db
       (db/set-alerts [(a/build-trigger-success)])
       (db/set-show-trigger-form nil)
       (db/set-trigger-form nil))))

(rf/reg-event-db
 :repo/trigger-build--failed
 (fn [db [_ err]]
   (db/set-alerts db [(a/build-trigger-failed err)])))

(rf/reg-event-fx
 :repo/load+edit
 (fn [{:keys [db]} _]
   (let [org-id (r/org-id db)]
     {:dispatch [:secure-request
                 :get-org
                 {:org-id org-id}
                 [:repo/load+edit--success]
                 [:repo/load+edit--failed]]
      :db (-> db
              (db/reset-edit-alerts)
              (db/unmark-saving))})))

(rf/reg-event-fx
 :repo/load+edit--success
 (fn [{:keys [db]} [_ resp]]
   (let [repo-id (r/repo-id db)
         match (->> (:body resp)
                    :repos
                    (filter (comp (partial = repo-id) :id))
                    (first))]
     {:dispatch [:org/load--success resp]
      :db (db/set-editing db (mc/update-existing match :github-id str))})))

(rf/reg-event-db
 :repo/load+edit--failed
 (fn [db [_ err]]
   (db/set-edit-alerts db [{:type :danger
                            :message (str "Unable to fetch repository info: " (u/error-msg err))}])))

(rf/reg-event-db
 :repo/name-changed
 (fn [db [_ v]]
   (db/update-editing db assoc :name v)))

(rf/reg-event-db
 :repo/main-branch-changed
 (fn [db [_ v]]
   (db/update-editing db assoc :main-branch v)))

(rf/reg-event-db
 :repo/url-changed
 (fn [db [_ v]]
   (db/update-editing db assoc :url v)))

(rf/reg-event-db
 :repo/public-toggled
 (fn [db [_ v]]
   (db/update-editing db assoc :public v)))

(rf/reg-event-db
 :repo/github-id-changed
 (fn [db [_ v]]
   (assoc-in db [db/editing :github-id] v)))

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

(rf/reg-event-db
 ::unwatch-github
 (fn [db _]
   (db/update-editing db assoc :github-id nil)))

(defn- parse-github-id [repo]
  (letfn [(parse [id]
            (when-not (empty? id)
              (u/parse-int id)))]
    (mc/update-existing repo :github-id parse)))

(rf/reg-event-fx
 :repo/save
 (fn [{:keys [db]} _]
   (let [params (-> (r/current db)
                    (r/path-params)
                    (select-keys [:org-id :repo-id]))
         new? (nil? (:repo-id params))]
     {:dispatch [:secure-request
                 (if new? :create-repo :update-repo)
                 (-> params
                     (assoc :repo (-> (db/editing db)
                                      (assoc :id (:repo-id params)
                                             :org-id (:org-id params))
                                      (parse-github-id))))
                 [:repo/save--success new?]
                 [:repo/save--failed new?]]
      :db (-> db
              (db/mark-saving)
              (db/reset-edit-alerts))})))

(rf/reg-event-db
 :repo/save--success
 (fn [db [_ new? {:keys [body]}]]
   (-> db
       (odb/replace-repo body)
       (db/unmark-saving)
       (db/set-edit-alerts [(if new?
                              a/repo-create-success
                              a/repo-update-success)]))))

(rf/reg-event-db
 :repo/save--failed
 (fn [db [_ new? err]]
   (-> db
       (db/set-edit-alerts [((if new?
                               a/repo-create-failed
                               a/repo-update-failed)
                             err)])
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

(defn- remove-repo [org repo-id]
  (update org :repos (partial remove (comp (partial = repo-id) :id))))

(rf/reg-event-fx
 :repo/delete--success
 (fn [{:keys [db]} _]
   (let [params (-> (r/current db) (r/path-params))
         repo-id (:repo-id params)
         repo (u/find-by-id repo-id (:repos (odb/get-org db)))]
     {:db (-> db
              (db/unmark-deleting)
              (odb/update-org remove-repo repo-id)
              (odb/set-alerts
               [{:type :info
                 :message (str "Repository " (:name repo) " has been deleted.")}]))
      :dispatch [:route/goto :page/org (select-keys params [:org-id])]})))

(rf/reg-event-db
 :repo/delete--failed
 (fn [db [_ err]]
   (-> db
       (db/unmark-deleting)
       (db/set-edit-alerts
        [{:type :danger
          :message (str "Unable to delete repository: " (u/error-msg err))}]))))

(rf/reg-event-db
 :repo/new
 (fn [db _]
   (db/set-editing db {})))

(rf/reg-event-fx
 :repo/lookup-github-id
 (fn [{:keys [db]} _]
   {:dispatch [:github/get-repo
               (:url (db/editing db))
               [:repo/lookup-github-id--success]
               [:repo/lookup-github-id--failed]]}))

(rf/reg-event-db
 :repo/lookup-github-id--success
 (fn [db [_ {:keys [id]}]]
   (db/update-editing db assoc :github-id (str id))))

(rf/reg-event-fx
 :repo/lookup-github-id--failed
 (u/req-error-handler-db
  (fn [db [_ err]]
    (db/set-edit-alerts db [(a/repo-lookup-github-id-failed err)]))))
