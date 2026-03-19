(ns monkey.ci.gui.job.events
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.build.db :as bdb]
            [monkey.ci.gui.job.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.loki :as loki]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def alerts ::alerts)
(def details-tabs-id ::details-tabs)

(rf/reg-event-fx
 :job/init
 (fn [{:keys [db]} [_ uid]]
   (lo/on-initialize db uid {:init-events
                             [[:org/maybe-load]
                              [:build/maybe-load]
                              [:tab/tab-changed details-tabs-id nil]]
                             :leave-event
                             [:job/leave uid]})))

(rf/reg-event-db
 :job/leave
 (fn [db [_ uid]]
   (-> db
       (lo/clear-all uid)
       (db/clear-expanded))))

(def route->id db/route->id)

(def params->build-sid (juxt :org-id :repo-id :build-id))

(rf/reg-event-fx
 :job/load-log-files
 ;; Fetches log files from Loki using a label search
 (fn [{:keys [db] :as ctx} [_ job :as evt]]
   (let [[org-id :as id] (db/db->job-id db)
         loader (lo/loader-evt-handler
                 id
                 (fn [& _]
                   [:secure-request
                    :get-log-label-values
                    (-> (loki/request-params id job)
                        (assoc :org-id org-id
                               :label "filename"))
                    [:job/load-log-files--success]
                    [:job/load-log-files--failed]]))]
     (loader ctx evt))))

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
                  [(a/job-fetch-logs-failed err)])))

(rf/reg-event-fx
 ;; Fetches log contents from old-style loki endpoints
 :job/load-loki-logs
 (fn [{:keys [db] :as ctx} [_ job path :as evt]]
   (let [[org-id :as id] (db/get-path-id db path)
         loader (lo/loader-evt-handler
                 id
                 (fn [& _]
                   ;; FIXME Loki limits the number of entries returned.  We should send an index stats
                   ;; request first and then fetch all entries.  This could lead to problems with large
                   ;; logs however, so we'll need to add some sort of pagination.
                   [:secure-request
                    :download-log
                    (-> (loki/request-params id job)
                        (assoc :org-id org-id
                               :limit 1000
                               :query (-> (loki/job-query id (:id job))
                                          (assoc "filename" path)
                                          (loki/query->str))))
                    [:job/load-logs--success path :loki]
                    [:job/load-logs--failed path :loki]]))]
     (loader ctx evt))))

(rf/reg-event-fx
 ;; Fetches log contents from log-ingested endpoints
 :job/load-log-ingest-logs
 (fn [{:keys [db] :as ctx} [_ job path :as evt]]
   (let [[org-id :as id] (db/get-path-id db path)
         loader (lo/loader-evt-handler
                 id
                 (fn [& _]
                   [:secure-request
                    :download-job-log
                    (-> (r/path-params (r/current db))
                        (assoc :file path))
                    [:job/load-logs--success path :log-ingest]
                    [:job/load-logs--failed path :log-ingest]]))]
     (loader ctx evt))))

(rf/reg-event-db
 :job/load-logs--success
 (fn [db [_ path src resp]]
   (letfn [(patch-result [resp]
             (if (= 204 (:status resp))
               (assoc resp :body {:src src})
               (update resp :body assoc :src src)))]
     (-> db
         (lo/on-success (db/get-path-id db path) (patch-result resp))
         (db/clear-alerts path)))))

(rf/reg-event-db
 :job/load-logs--failed
 (fn [db [_ path _ err]]
   (lo/on-failure db (db/get-path-id db path) a/job-fetch-logs-failed err)))

(rf/reg-event-fx
 :job/maybe-load-logs
 (fn [{:keys [db]} [_ job path src]]
   ;; Only fetch if job is running or if this is the first time
   (let [running? (= :running (:status job))]
     (when (or running? (not (db/get-logs db path)))
       {:dispatch [(case src
                     :loki :job/load-loki-logs
                     :log-ingest :job/load-log-ingest-logs)
                   job path]}))))

(rf/reg-event-fx
 :job/toggle-logs
 (fn [{:keys [db]} [_ idx {:keys [out err log-src]}]]
   (let [job-id (last (db/db->job-id db))
         job (get-in (bdb/get-build db) [:script :jobs job-id])
         exp? (db/log-expanded? db idx)]
     (cond-> {:db (db/set-log-expanded db idx (not exp?))}
       (not exp?)
       ;; Only fetch logs if we know they exist
       (assoc :dispatch-n
              (->> [out err]
                   (filter some?)
                   (mapv (fn [p] [:job/maybe-load-logs job p log-src]))))))))

(rf/reg-event-fx
 :job/unblock
 (fn [{:keys [db]} [_ job]]
   (let [sid (-> (r/current db)
                 (r/path-params)
                 (select-keys [:org-id :repo-id :build-id]))]
     {:dispatch [:secure-request
                 :unblock-job
                 (assoc sid :job-id (:id job))
                 [:job/unblock--success]
                 [:job/unblock--failure]]
      :db (db/set-unblocking db)})))

(rf/reg-event-db
 :job/unblock--success
 (fn [db _]
   ;; Do not update job, an event should take care of this
   (db/reset-unblocking db)))

(rf/reg-event-db
 :job/unblock--failure
 (fn [db err]
   (-> db
       (db/reset-unblocking)
       (db/set-alerts [(a/job-unblock-failed err)]))))

(rf/reg-event-db
 :job/wrap-logs-changed
 (fn [db [_ v]]
   (db/set-wrap-logs db v)))
