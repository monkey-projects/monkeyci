(ns monkey.ci.gui.job.events
  (:require [monkey.ci.gui.build.db :as bdb]
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
                             [[:customer/maybe-load]
                              [:build/maybe-load]
                              [:tab/tab-changed details-tabs-id nil]]
                             :leave-event
                             [:job/leave uid]})))

(rf/reg-event-db
 :job/leave
 (fn [db [_ uid]]
   (lo/clear-all db uid)))

(def route->id db/route->id)

(def params->build-sid (juxt :customer-id :repo-id :build-id))

(rf/reg-event-fx
 :job/load-log-files
 (fn [{:keys [db] :as ctx} [_ job :as evt]]
   (let [[cust-id :as id] (db/db->job-id db)
         loader (lo/loader-evt-handler
                 id
                 (fn [& _]
                   [:secure-request
                    :get-log-label-values
                    (-> (loki/request-params id job)
                        (assoc :customer-id cust-id
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
                  [{:type :danger
                    :message (str "Failed to fetch logs: " (u/error-msg err))}])))

(rf/reg-event-fx
 :job/load-logs
 (fn [{:keys [db] :as ctx} [_ job path :as evt]]
   (let [[cust-id :as id] (db/get-path-id db path)
         loader (lo/loader-evt-handler
                 id
                 (fn [& _]
                   ;; FIXME Loki limits the number of entries returned.  We should send an index stats
                   ;; request first and then fetch all entries.  This could lead to problems with large
                   ;; logs however, so we'll need to add some sort of pagination.
                   [:secure-request
                    :download-log
                    (-> (loki/request-params id job)
                        (assoc :customer-id cust-id
                               :limit 1000
                               :query (-> (loki/job-query id (:id job))
                                          (assoc "filename" path)
                                          (loki/query->str))))
                    [:job/load-logs--success path]
                    [:job/load-logs--failed path]]))]
     (loader ctx evt))))

(rf/reg-event-db
 :job/load-logs--success
 (fn [db [_ path resp]]
   (-> db
       (lo/on-success (db/get-path-id db path) resp)
       (db/clear-alerts path))))

(rf/reg-event-db
 :job/load-logs--failed
 (fn [db [_ path err]]
   (lo/on-failure db (db/get-path-id db path) "Failed to fetch logs: " err)))

(rf/reg-event-fx
 :job/toggle-logs
 (fn [{:keys [db]} [_ idx]]
   (let [job-id (db/db->job-id db)
         job (get-in (bdb/get-build db) [:script :jobs job-id])]
     ;; TODO Only fetch logs if we know they exist
     {:dispatch-n [[:job/load-logs job (str idx "_out.log")]
                   [:job/load-logs job (str idx "_err.log")]]
      :db (db/set-log-expanded db idx true)})))
