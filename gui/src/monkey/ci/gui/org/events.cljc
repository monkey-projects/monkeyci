(ns monkey.ci.gui.org.events
  (:require [medley.core :as mc]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.home.db :as hdb]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.org.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.server-events]
            [monkey.ci.gui.time :as t]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :org/init
 (fn [{:keys [db]} [_ id]]
   (lo/on-initialize
    db db/org
    {:init-events         [[:org/load id]]
     :leave-event         [:org/leave]
     :event-handler-event [:org/handle-event]})))

(rf/reg-event-fx
 :org/leave
 (fn [{:keys [db]} _]
   (lo/on-leave db db/org)))

(rf/reg-event-fx
 :org/load
 (lo/loader-evt-handler
  db/org
  (fn [_ _ [_ id]]
    [:secure-request
     :get-org
     {:org-id id}
     [:org/load--success]
     [:org/load--failed id]])))

(rf/reg-event-fx
 :org/maybe-load
 (fn [{:keys [db]} [_ id]]
   (let [existing (db/get-org db)
         id (or id (r/org-id db))]
     (when-not (= (:id existing) id)
       {:dispatch [:org/load id]}))))

(rf/reg-event-db
 :org/load--success
 (fn [db [_ resp]]
   (lo/on-success db db/org resp)))

(rf/reg-event-db
 :org/load--failed
 (fn [db [_ id err]]
   (lo/on-failure db db/org (a/org-details-failed id) err)))

(rf/reg-event-fx
 :org/load-latest-builds
 (fn [{:keys [db]} _]
   {:dispatch [:secure-request
               :get-org-latest-builds
               {:org-id (r/org-id db)}
               [:org/load-latest-builds--success]
               [:org/load-latest-builds--failed]]}))

(rf/reg-event-db
 :org/load-latest-builds--success
 (fn [db [_ {:keys [body]}]]
   (db/set-latest-builds db (->> body
                                 (group-by :repo-id)
                                 (mc/map-vals first)))))

(rf/reg-event-db
 :org/load-latest-builds--failed
 (fn [db [_ err]]
   (db/set-alerts db [(a/org-latest-builds-failed err)])))

(rf/reg-event-fx
 :org/load-bb-webhooks
 (fn [{:keys [db]} _]
   (let [org (db/get-org db)]
     {:dispatch [:secure-request
                 :search-bitbucket-webhooks
                 {:org-id (:id org)}
                 [:org/load-bb-webhooks--success]
                 [:org/load-bb-webhooks--failed]]})))

(rf/reg-event-db
 :org/load-bb-webhooks--success
 (fn [db [_ {:keys [body]}]]
   (db/set-bb-webhooks db body)))

(rf/reg-event-db
 :org/load-bb-webhooks--failed
 (fn [db [_ err]]
   (db/set-repo-alerts db [(a/bitbucket-webhooks-failed err)])))

(rf/reg-event-fx
 :repo/watch-github
 (fn [{:keys [db]} [_ repo]]
   (log/debug "Watching repo:" repo)
   (let [org-id (r/org-id db)]
     {:dispatch [:secure-request
                 :watch-github-repo
                 {:repo {:name (:name repo)
                         :url (:clone-url repo)
                         :org-id org-id
                         :github-id (:id repo)
                         :token (ldb/bitbucket-token db)}
                  :org-id org-id}
                 [:repo/watch--success]
                 [:repo/watch--failed]]})))

(defn- clone-url [{:keys [is-private] :as repo}]
  (->> (get-in repo [:links :clone])
       ;; Use ssh url for private repos
       (filter (comp (partial = (if is-private "ssh" "https")) :name))
       (first)
       :href))

(rf/reg-event-fx
 :repo/watch-bitbucket
 (fn [{:keys [db]} [_ repo]]
   (log/debug "Watching repo:" (str repo))
   (let [org-id (r/org-id db)
         token (ldb/bitbucket-token db)]
     {:dispatch [:secure-request
                 :watch-bitbucket-repo
                 {:repo {:name (:name repo)
                         :url (clone-url repo)
                         :orgomer-id org-id
                         :workspace (get-in repo [:workspace :slug])
                         :repo-slug (:slug repo)
                         :token token}
                  :org-id org-id}
                 [:repo/watch--success]
                 [:repo/watch--failed]]})))

(rf/reg-event-db
 :repo/watch--success
 (fn [db [_ {:keys [body]}]]
   (db/update-org db update :repos conj body)))

(rf/reg-event-db
 :repo/watch--failed
 (fn [db [_ err]]
   (db/set-repo-alerts db [(a/repo-watch-failed err)])))

(rf/reg-event-fx
 :repo/unwatch-github
 (fn [{:keys [db]} [_ {:keys [:monkeyci/repo]}]]
   {:dispatch [:secure-request
               :unwatch-github-repo
               {:repo-id (:id repo)
                :org-id (r/org-id db)}
               [:repo/unwatch--success]
               [:repo/unwatch--failed]]}))

(rf/reg-event-fx
 :repo/unwatch-bitbucket
 (fn [{:keys [db]} [_ repo]]
   (let [wh (:monkeyci/webhook repo)]
     {:dispatch [:secure-request
                 :unwatch-bitbucket-repo
                 (-> (select-keys wh [:org-id :repo-id])
                     (assoc-in [:repo :token] (ldb/bitbucket-token db)))
                 [:repo/unwatch--success]
                 [:repo/unwatch--failed]]})))

(rf/reg-event-db
 :repo/unwatch--success
 (fn [db [_ {:keys [body]}]]
   (db/replace-repo db body)))

(rf/reg-event-db
 :repo/unwatch--failed
 (fn [db [_ err]]
   (db/set-repo-alerts db [(a/repo-unwatch-failed err)])))

(rf/reg-event-fx
 :org/create
 (fn [{:keys [db]} [_ org]]
   {:dispatch [:secure-request
               :create-org
               {:org (mc/map-vals first org)}
               [:org/create--success]
               [:org/create--failed]]
    :db (-> db
            (db/mark-org-creating)
            (db/reset-create-alerts))}))

(rf/reg-event-fx
 :org/create--success
 (fn [{:keys [db]} [_ {:keys [body]}]]
   {:db (-> db
            (db/unmark-org-creating)
            (db/set-org body)
            (hdb/set-orgs (conj (vec (hdb/get-orgs db)) body))
            (lo/set-alerts db/org
                           [(a/org-create-success body)]))
    ;; Redirect to org page
    :dispatch [:route/goto :page/org {:org-id (:id body)}]}))

(rf/reg-event-db
 :org/create--failed
 (fn [db [_ err]]
   (db/set-create-alerts db [(a/org-create-failed err)])))

(rf/reg-event-fx
 :org/load-recent-builds
 (lo/loader-evt-handler
  db/recent-builds
  (fn [_ _ [_ org-id]]
    [:secure-request
     :get-recent-builds
     {:org-id org-id
      :n 10}
     [:org/load-recent-builds--success]
     [:org/load-recent-builds--failed]])))

(rf/reg-event-db
 :org/load-recent-builds--success
 (fn [db [_ resp]]
   (lo/on-success db db/recent-builds resp)))

(rf/reg-event-db
 :org/load-recent-builds--failed
 (fn [db [_ err]]
   (lo/on-failure db db/recent-builds a/org-recent-builds-failed err)))

(rf/reg-event-fx
 :org/load-stats
 [(rf/inject-cofx :time/now)]
 (lo/loader-evt-handler
  db/stats
  (fn [_ ctx [_ org-id days]]
    [:secure-request
     :get-org-stats
     (cond-> {:org-id org-id}
       days (assoc :since (-> (:time/now ctx) (t/parse-epoch) (t/minus-days days) (t/to-epoch))))
     [:org/load-stats--success]
     [:org/load-stats--failed]])))

(rf/reg-event-db
 :org/load-stats--success
 (fn [db [_ resp]]
   (lo/on-success db db/stats resp)))

(rf/reg-event-db
 :org/load-stats--failed
 (fn [db [_ err]]
   (lo/on-failure db db/stats a/org-stats-failed err)))

(rf/reg-event-fx
 :org/load-credits
 (lo/loader-evt-handler
  db/credits
  (fn [_ ctx [_ org-id days]]
    [:secure-request
     :get-org-credits
     {:org-id org-id}
     [:org/load-credits--success]
     [:org/load-credits--failed]])))

(rf/reg-event-db
 :org/load-credits--success
 (fn [db [_ resp]]
   (lo/on-success db db/credits resp)))

(rf/reg-event-db
 :org/load-credits--failed
 (fn [db [_ err]]
   (lo/on-failure db db/credits a/org-credits-failed err)))

(rf/reg-event-db
 :org/handle-event
 (fn [db [_ {:keys [build] :as evt}]]
   (when (and (= :build/updated (:type evt))
              (lo/loaded? db db/recent-builds))
     (letfn [(update-build [builds]
               (let [sid (juxt :org-id :repo-id :build-id)]
                 (->> (if-let [match (->> builds
                                          (filter (comp (partial = (sid build)) sid))
                                          (first))]
                        (replace {match build} builds)
                        (conj builds build))
                      (sort-by :start-time)
                      (reverse))))]
       (lo/update-value db db/recent-builds update-build)))))

(rf/reg-event-db
 :org/group-by-lbl-changed
 (fn [db [_ val]]
   (db/set-group-by-lbl db val)))

(rf/reg-event-db
 :org/repo-filter-changed
 (fn [db [_ val]]
   (db/set-repo-filter db val)))

(rf/reg-event-db
 :org/ext-repo-filter-changed
 (fn [db [_ val]]
   (db/set-ext-repo-filter db val)))
