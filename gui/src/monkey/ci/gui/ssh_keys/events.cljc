(ns monkey.ci.gui.ssh-keys.events
  (:require [monkey.ci.gui.loader :as lo]
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
 (fn [_ [_ cust-id]]
   {:dispatch [:secure-request
               :get-customer-ssh-keys
               {:customer-id cust-id}
               [:ssh-keys/load--success]
               [:ssh-keys/load--failed]]}))
