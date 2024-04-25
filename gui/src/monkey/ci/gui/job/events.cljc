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
 :job/load-logs
 (fn [{:keys [db]} [_ job]]
   (when job
     (let [params (r/path-params (r/current db))
           sid (params->build-sid params)]
       {:http-xhrio (-> (loki/job-logs-request sid job)
                        (assoc :on-success [:job/load-logs--success]
                               :on-failure [:job/load-logs--failed])
                        (assoc-in [:headers "X-Scope-OrgID"] (first sid)))
        :db (db/set-alerts db
                           [{:type :info
                             :message "Fetching logs..."}])}))))

(rf/reg-event-db
 :job/load-logs--success
 (fn [db [_ logs]]
   (-> db
       (db/clear-alerts)
       (db/set-logs logs))))

(rf/reg-event-db
 :job/load-logs--failed
 (fn [db [_ err]]
   (db/set-alerts db
                  [{:type :danger
                    :message (str "Failed to fetch logs: " (u/error-msg err))}])))
