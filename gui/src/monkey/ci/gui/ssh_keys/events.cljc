(ns monkey.ci.gui.ssh-keys.events
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.ssh-keys.db :as db]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :ssh-keys/initialize
 (fn [{:keys [db]} [_ cust-id]]
   (lo/on-initialize db db/id
                     {:init-events [[:ssh-keys/load cust-id]]})))

(rf/reg-event-fx
 :ssh-keys/load
 (lo/loader-evt-handler
  db/id
  (fn [_ _ [_ cust-id]]
    [:secure-request
     :get-customer-ssh-keys
     {:customer-id cust-id}
     [:ssh-keys/load--success]
     [:ssh-keys/load--failed]])))

(rf/reg-event-db
 :ssh-keys/load--success
 (fn [db [_ resp]]
   (lo/on-success db db/id resp)))

(rf/reg-event-db
 :ssh-keys/load--failed
 (fn [db [_ err]]
   (lo/on-failure db db/id a/cust-ssh-keys-failed err)))
