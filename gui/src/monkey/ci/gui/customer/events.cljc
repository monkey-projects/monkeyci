(ns monkey.ci.gui.customer.events
  (:require [medley.core :as mc]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.home.db :as hdb]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.server-events]
            [monkey.ci.gui.time :as t]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :customer/init
 (fn [{:keys [db]} [_ id]]
   (lo/on-initialize
    db db/customer
    {:init-events         [[:customer/load id]]
     :leave-event         [:customer/leave]
     :event-handler-event [:customer/handle-event]})))

(rf/reg-event-fx
 :customer/leave
 (fn [{:keys [db]} _]
   (lo/on-leave db db/customer)))

(rf/reg-event-fx
 :customer/load
 (lo/loader-evt-handler
  db/customer
  (fn [_ _ [_ id]]
    [:secure-request
     :get-customer
     {:customer-id id}
     [:customer/load--success]
     [:customer/load--failed id]])))

(rf/reg-event-fx
 :customer/maybe-load
 (fn [{:keys [db]} [_ id]]
   (let [existing (db/get-customer db)
         id (or id (r/customer-id db))]
     (when-not (= (:id existing) id)
       {:dispatch [:customer/load id]}))))

(rf/reg-event-db
 :customer/load--success
 (fn [db [_ resp]]
   (lo/on-success db db/customer resp)))

(rf/reg-event-db
 :customer/load--failed
 (fn [db [_ id err]]
   (lo/on-failure db db/customer (a/cust-details-failed id) err)))

(rf/reg-event-fx
 :customer/load-latest-builds
 (fn [{:keys [db]} _]
   {:dispatch [:secure-request
               :get-customer-latest-builds
               {:customer-id (r/customer-id db)}
               [:customer/load-latest-builds--success]
               [:customer/load-latest-builds--failed]]}))

(rf/reg-event-db
 :customer/load-latest-builds--success
 (fn [db [_ {:keys [body]}]]
   (db/set-latest-builds db (->> body
                                 (group-by :repo-id)
                                 (mc/map-vals first)))))

(rf/reg-event-db
 :customer/load-latest-builds--failed
 (fn [db [_ err]]
   (db/set-alerts db [(a/cust-latest-builds-failed err)])))

(rf/reg-event-fx
 :customer/load-bb-webhooks
 (fn [{:keys [db]} _]
   (let [cust (db/get-customer db)]
     {:dispatch [:secure-request
                 :search-bitbucket-webhooks
                 {:customer-id (:id cust)}
                 [:customer/load-bb-webhooks--success]
                 [:customer/load-bb-webhooks--failed]]})))

(rf/reg-event-db
 :customer/load-bb-webhooks--success
 (fn [db [_ {:keys [body]}]]
   (db/set-bb-webhooks db body)))

(rf/reg-event-db
 :customer/load-bb-webhooks--failed
 (fn [db [_ err]]
   (db/set-repo-alerts db [(a/bitbucket-webhooks-failed err)])))

(rf/reg-event-fx
 :repo/watch-github
 (fn [{:keys [db]} [_ repo]]
   (log/debug "Watching repo:" repo)
   (let [cust-id (r/customer-id db)]
     {:dispatch [:secure-request
                 :watch-github-repo
                 {:repo {:name (:name repo)
                         :url (:clone-url repo)
                         :customer-id cust-id
                         :github-id (:id repo)
                         :token (ldb/bitbucket-token db)}
                  :customer-id cust-id}
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
   (let [cust-id (r/customer-id db)
         token (ldb/bitbucket-token db)]
     {:dispatch [:secure-request
                 :watch-bitbucket-repo
                 {:repo {:name (:name repo)
                         :url (clone-url repo)
                         :customer-id cust-id
                         :workspace (get-in repo [:workspace :slug])
                         :repo-slug (:slug repo)
                         :token token}
                  :customer-id cust-id}
                 [:repo/watch--success]
                 [:repo/watch--failed]]})))

(rf/reg-event-db
 :repo/watch--success
 (fn [db [_ {:keys [body]}]]
   (db/update-customer db update :repos conj body)))

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
                :customer-id (r/customer-id db)}
               [:repo/unwatch--success]
               [:repo/unwatch--failed]]}))

(rf/reg-event-fx
 :repo/unwatch-bitbucket
 (fn [{:keys [db]} [_ repo]]
   (let [wh (:monkeyci/webhook repo)]
     {:dispatch [:secure-request
                 :unwatch-bitbucket-repo
                 (-> (select-keys wh [:customer-id :repo-id])
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
 :customer/create
 (fn [{:keys [db]} [_ cust]]
   {:dispatch [:secure-request
               :create-customer
               {:customer (mc/map-vals first cust)}
               [:customer/create--success]
               [:customer/create--failed]]
    :db (-> db
            (db/mark-customer-creating)
            (db/reset-create-alerts))}))

(rf/reg-event-fx
 :customer/create--success
 (fn [{:keys [db]} [_ {:keys [body]}]]
   {:db (-> db
            (db/unmark-customer-creating)
            (db/set-customer body)
            (hdb/set-customers (conj (vec (hdb/get-customers db)) body))
            (lo/set-alerts db/customer
                           [(a/cust-create-success body)]))
    ;; Redirect to customer page
    :dispatch [:route/goto :page/customer {:customer-id (:id body)}]}))

(rf/reg-event-db
 :customer/create--failed
 (fn [db [_ err]]
   (db/set-create-alerts db [(a/cust-create-failed err)])))

(rf/reg-event-fx
 :customer/load-recent-builds
 (lo/loader-evt-handler
  db/recent-builds
  (fn [_ _ [_ cust-id]]
    [:secure-request
     :get-recent-builds
     {:customer-id cust-id}
     [:customer/load-recent-builds--success]
     [:customer/load-recent-builds--failed]])))

(rf/reg-event-db
 :customer/load-recent-builds--success
 (fn [db [_ resp]]
   (lo/on-success db db/recent-builds resp)))

(rf/reg-event-db
 :customer/load-recent-builds--failed
 (fn [db [_ err]]
   (lo/on-failure db db/recent-builds a/cust-recent-builds-failed err)))

(rf/reg-event-fx
 :customer/load-stats
 [(rf/inject-cofx :time/now)]
 (lo/loader-evt-handler
  db/stats
  (fn [_ ctx [_ cust-id days]]
    [:secure-request
     :get-customer-stats
     (cond-> {:customer-id cust-id}
       days (assoc :since (-> (:time/now ctx) (t/parse-epoch) (t/minus-days days) (t/to-epoch))))
     [:customer/load-stats--success]
     [:customer/load-stats--failed]])))

(rf/reg-event-db
 :customer/load-stats--success
 (fn [db [_ resp]]
   (lo/on-success db db/stats resp)))

(rf/reg-event-db
 :customer/load-stats--failed
 (fn [db [_ err]]
   (lo/on-failure db db/stats a/cust-stats-failed err)))

(rf/reg-event-fx
 :customer/load-credits
 (lo/loader-evt-handler
  db/credits
  (fn [_ ctx [_ cust-id days]]
    [:secure-request
     :get-customer-credits
     {:customer-id cust-id}
     [:customer/load-credits--success]
     [:customer/load-credits--failed]])))

(rf/reg-event-db
 :customer/load-credits--success
 (fn [db [_ resp]]
   (lo/on-success db db/credits resp)))

(rf/reg-event-db
 :customer/load-credits--failed
 (fn [db [_ err]]
   (lo/on-failure db db/credits a/cust-credits-failed err)))

(rf/reg-event-db
 :customer/handle-event
 (fn [db [_ {:keys [build] :as evt}]]
   (when (and (= :build/updated (:type evt))
              (lo/loaded? db db/recent-builds))
     (letfn [(update-build [builds]
               (let [sid (juxt :customer-id :repo-id :build-id)]
                 (->> (if-let [match (->> builds
                                          (filter (comp (partial = (sid build)) sid))
                                          (first))]
                        (replace {match build} builds)
                        (conj builds build))
                      (sort-by :start-time)
                      (reverse))))]
       (lo/update-value db db/recent-builds update-build)))))

(rf/reg-event-db
 :customer/group-by-lbl-changed
 (fn [db [_ val]]
   (db/set-group-by-lbl db val)))

(rf/reg-event-db
 :customer/repo-filter-changed
 (fn [db [_ val]]
   (db/set-repo-filter db val)))

(rf/reg-event-db
 :customer/ext-repo-filter-changed
 (fn [db [_ val]]
   (db/set-ext-repo-filter db val)))
