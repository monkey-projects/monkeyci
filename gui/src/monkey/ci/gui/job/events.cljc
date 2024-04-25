(ns monkey.ci.gui.job.events
  (:require [monkey.ci.gui.job.db :as db]
            [monkey.ci.gui.loki :as loki]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def alerts ::alerts)

(rf/reg-event-fx
 :job/init
 (fn [_ _]
   {:dispatch-n [[:customer/maybe-load]
                 [:build/maybe-load]]}))

(def params->build-sid (juxt :customer-id :repo-id :build-id))

(rf/reg-event-fx
 :job/load-log-files
 (fn [{:keys [db]} [_ job]]
   (when job
     (let [params (r/path-params (r/current db))
           sid (params->build-sid params)]
       {:http-xhrio (-> (loki/filename-values-request sid job)
                        (assoc :on-success [:job/load-log-files--success]
                               :on-failure [:job/load-log-files--failed]))
        :db (db/set-alerts db
                           [{:type :info
                             :message "Seaching logs..."}])}))))

(rf/reg-event-db
 :job/load-log-files--success
 (fn [db [_ {:keys [data]}]]
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
       {:http-xhrio (-> (loki/query-range-request sid job)
                        (loki/with-query (-> (loki/job-query sid (:id job))
                                             (assoc "filename" path)
                                             (loki/query->str)))
                        (assoc-in [:params :limit] 1000)
                        (assoc :on-success [:job/load-logs--success path]
                               :on-failure [:job/load-logs--failed path]))
        :db (db/set-alerts db
                           path
                           [{:type :info
                             :message "Fetching logs..."}])}))))

(rf/reg-event-db
 :job/load-logs--success
 (fn [db [_ path logs]]
   (-> db
       (db/clear-alerts path)
       (db/set-logs path logs))))

(rf/reg-event-db
 :job/load-logs--failed
 (fn [db [_ path err]]
   (println "Got error:" err)
   (db/set-alerts db
                  path
                  [{:type :danger
                    :message (str "Failed to fetch logs: " (u/error-msg err))}])))
