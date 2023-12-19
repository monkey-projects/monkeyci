(ns monkey.ci.gui.repo.events
  (:require [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.repo.db :as db]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :repo/load
 (fn [{:keys [db]} [_ cust-id]]
   (let [existing (cdb/customer db)]
     (when-not existing
       {:dispatch [:customer/load cust-id]}))))

(rf/reg-event-fx
 :builds/load
 (fn [{:keys [db]} _]
   (let [params (get-in db [:route/current :parameters :path])]
     (println "Params:" params)
     {:db (db/set-alerts db [{:type :info
                              :message "Loading builds for repository..."}])
      :dispatch [:martian.re-frame/request
                 :get-builds
                 (select-keys params [:customer-id :project-id :repo-id])
                 [:builds/load--success]
                 [:builds/load--failed]]})))

(rf/reg-event-db
 :builds/load--success
 (fn [db [_ {builds :body}]]
   (db/set-builds db builds)))

(rf/reg-event-db
 :builds/load--failed
 (fn [db [_ err op]]
   (db/set-alerts db [{:type :danger
                       :message (str "Could not load builds: " (or (:message err) (str err)))}])))
