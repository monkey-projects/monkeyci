(ns monkey.ci.gui.admin.credits.events
  (:require [medley.core :as mc]
            [monkey.ci.gui.admin.credits.db :as db]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.time :as t]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :credits/org-search
 (fn [{:keys [db]} [_ details]]
   (let [f (-> details (get :org-filter) first)]
     ;; Search by name and id
     {:dispatch-n [[:credits/org-search-by-name details]
                   [:credits/org-search-by-id details]]
      :db (db/reset-orgs db)})))

(def org-filter (comp first :org-filter))

(def id->key
  {db/org-by-id :id
   db/org-by-name :name})

(defn- search-org-req [id _ [_ details]]
  [:secure-request
   :search-orgs
   {(id->key id) (org-filter details)}
   [:credits/org-search--success id]
   [:credits/org-search--failed id]])

(rf/reg-event-fx
 :credits/org-search-by-name
 (lo/loader-evt-handler
  db/org-by-name
  search-org-req))

(rf/reg-event-fx
 :credits/org-search-by-id
 (lo/loader-evt-handler
  db/org-by-id
  search-org-req))

(rf/reg-event-db
 :credits/org-search--success
 (fn [db [_ id resp]]
   (lo/on-success db id resp)))

(rf/reg-event-db
 :credits/org-search--failed
 (fn [db [_ id resp]]
   (lo/on-failure db id a/org-search-failed resp)))

(rf/reg-event-fx
 :credits/load-issues
 (lo/loader-evt-handler
  db/issues
  (fn [_ _ [_ org-id]]
    [:secure-request
     :get-credit-issues
     {:org-id org-id}
     [:credits/load-issues--success]
     [:credits/load-issues--failed]])))

(rf/reg-event-db
 :credits/load-issues--success
 (fn [db [_ resp]]
   (lo/on-success db db/issues resp)))

(rf/reg-event-db
 :credits/load-issues--failed
 (fn [db [_ resp]]
   (lo/on-failure db db/issues a/credit-issues-failed resp)))

(rf/reg-event-db
 :credits/new-issue
 (fn [db _]
   (db/show-issue-form db)))

(rf/reg-event-db
 :credits/cancel-issue
 (fn [db _]
   (db/hide-issue-form db)))

(def date->epoch (comp t/to-epoch t/parse-iso))

(rf/reg-event-fx
 :credits/save-issue
 (fn [{:keys [db]} [_ params]]
   {:dispatch [:secure-request
               :create-credit-issue
               {:credits
                (-> (select-keys params [:reason :amount :from-time])
                    (as-> t (mc/map-vals first t))
                    (mc/update-existing :from-time date->epoch))
                :org-id (r/org-id db)}
               [:credits/save-issue--success]
               [:credits/save-issue--failed]]
    :db (-> db
            (db/set-issue-saving)
            (db/reset-issue-alerts))}))

(rf/reg-event-db
 :credits/save-issue--success
 (fn [db [_ {resp :body}]]
   (-> db
       (db/reset-issue-saving)
       (db/hide-issue-form)
       (db/update-issues conj resp)
       (db/set-issue-alerts [a/credit-issue-save-success]))))

(rf/reg-event-db
 :credits/save-issue--failed
 (fn [db [_ resp]]
   (-> db
       (db/reset-issue-saving)
       (db/set-issue-alerts [(a/credit-issue-save-failed resp)]))))

(rf/reg-event-fx
 :credits/load-subs
 (lo/loader-evt-handler
  db/subscriptions
  (fn [_ _ [_ org-id]]
    [:secure-request
     :get-credit-subs
     {:org-id org-id}
     [:credits/load-subs--success]
     [:credits/load-subs--failed]])))

(rf/reg-event-db
 :credits/load-subs--success
 (fn [db [_ resp]]
   (lo/on-success db db/subscriptions resp)))

(rf/reg-event-db
 :credits/load-subs--failed
 (fn [db [_ resp]]
   (lo/on-failure db db/subscriptions a/credit-subs-failed resp)))

(rf/reg-event-db
 :credits/new-sub
 (fn [db _]
   (db/show-sub-form db)))

(rf/reg-event-db
 :credits/cancel-sub
 (fn [db _]
   (db/hide-sub-form db)))

(rf/reg-event-fx
 :credits/save-sub
 (fn [{:keys [db]} [_ params]]
   {:dispatch [:secure-request
               :create-credit-sub
               {:sub
                (-> (select-keys params [:reason :amount :valid-from :valid-until])
                    (as-> t (mc/map-vals first t))
                    (mc/update-existing :valid-from date->epoch)
                    (mc/update-existing :valid-until date->epoch)
                    (as-> t (mc/filter-vals some? t)))
                :org-id (r/org-id db)}
               [:credits/save-sub--success]
               [:credits/save-sub--failed]]
    :db (-> db
            (db/set-sub-saving)
            (db/reset-sub-alerts))}))

(rf/reg-event-db
 :credits/save-sub--success
 (fn [db [_ {resp :body}]]
   (-> db
       (db/reset-sub-saving)
       (db/hide-sub-form)
       (db/update-subs conj resp)
       (db/set-sub-alerts [a/sub-save-success]))))

(rf/reg-event-db
 :credits/save-sub--failed
 (fn [db [_ resp]]
   (-> db
       (db/reset-sub-saving)
       (db/set-sub-alerts [(a/sub-save-failed resp)]))))

(rf/reg-event-fx
 :credits/issue-all
 (fn [{:keys [db]} [_ data]]
   {:dispatch [:secure-request
               :credits-issue-all
               {:issue-all {:date (-> data :date first)}}
               [:credits/issue-all--success]
               [:credits/issue-all--failed]]
    :db (-> db
            (db/set-issuing-all)
            (db/reset-issue-all-alerts))}))

(rf/reg-event-db
 :credits/issue-all--success
 (fn [db [_ {result :body}]]
   (-> db
       (db/reset-issuing-all)
       (db/set-issue-all-alerts [(a/credit-issue-all-success (:credits result))]))))

(rf/reg-event-db
 :credits/issue-all--failed
 (fn [db [_ err]]
   (-> db
       (db/reset-issuing-all)
       (db/set-issue-all-alerts [(a/credit-issue-all-failed err)]))))
