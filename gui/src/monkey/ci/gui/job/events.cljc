(ns monkey.ci.gui.job.events
  (:require [monkey.ci.gui.job.db :as db]
            [monkey.ci.gui.loki :as loki]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def alerts ::alerts)
(def log-tabs-id ::log-tabs)

(rf/reg-event-fx
 :job/init
 (fn [{:keys [db]} _]
   {:dispatch-n [[:customer/maybe-load]
                 [:build/maybe-load]
                 [:tab/tab-changed log-tabs-id nil]]
    :db (-> db
            (db/clear-alerts)
            (db/set-log-files nil)
            (db/clear-log-files))}))

(def params->build-sid (juxt :customer-id :repo-id :build-id))

(rf/reg-event-fx
 :job/load-log-files
 (fn [{:keys [db]} [_ job]]
   (when job
     (let [params (r/path-params (r/current db))
           sid (params->build-sid params)]
       {:dispatch [:secure-request
                   :get-log-label-values
                   (-> (loki/request-params sid job)
                       (assoc :customer-id (:customer-id params)
                              :label "filename"))
                   [:job/load-log-files--success]
                   [:job/load-log-files--failed]]
        :db (db/set-alerts db
                           [{:type :info
                             :message "Seaching logs..."}])}))))

(rf/reg-event-db
 :job/load-log-files--success
 (fn [db [_ {{:keys [data]} :body}]]
   (-> db
       (db/clear-alerts)
       (db/set-log-files data))))

(rf/reg-event-db
 :job/load-log-files--failed
 (fn [db [_ err]]
   (db/set-alerts db
                  [{:type :danger
                    :message (str "Failed to fetch logs: " (u/error-msg err))}])))

(rf/reg-event-fx
 :job/load-logs
 (fn [{:keys [db]} [_ job path]]
   (when job
     (let [params (r/path-params (r/current db))
           sid (params->build-sid params)]
       ;; FIXME Loki limits the number of entries returned.  We should send an index stats
       ;; request first and then fetch all entries.  This could lead to problems with large
       ;; logs however, so we'll need to add some sort of pagination.
       {:dispatch [:secure-request
                   :download-log
                   (-> (loki/request-params sid job)
                       (assoc :customer-id (:customer-id params)
                              :limit 1000
                              :query (-> (loki/job-query sid (:id job))
                                         (assoc "filename" path)
                                         (loki/query->str))))
                   [:job/load-logs--success path]
                   [:job/load-logs--failed path]]
        :db (db/set-alerts db
                           path
                           [{:type :info
                             :message "Fetching logs..."}])}))))

(rf/reg-event-db
 :job/load-logs--success
 (fn [db [_ path {logs :body}]]
   (-> db
       (db/clear-alerts path)
       (db/set-logs path logs))))

(rf/reg-event-db
 :job/load-logs--failed
 (fn [db [_ path err]]
   (db/set-alerts db
                  path
                  [{:type :danger
                    :message (str "Failed to fetch logs: " (u/error-msg err))}])))
