(ns monkey.ci.gui.customer.events
  (:require [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :customer/load
 (fn [{:keys [db]} [_ id]]
   (log/debug "Loading customer:" id)
   {:db (-> db (db/set-loading)
            (db/set-alerts [{:type :info
                             :message "Retrieving customer information..."}]))
    :dispatch [:secure-request
               :get-customer
               {:customer-id id}
               [:customer/load--success]
               [:customer/load--failed id]]}))

(rf/reg-event-db
 :customer/load--success
 (fn [db [_ {cust :body}]]
   (log/debug "Customer details loaded:" cust)
   (-> db
       (db/unset-loading)
       (db/set-customer cust)
       (db/reset-alerts))))

(rf/reg-event-db
 :customer/load--failed
 (fn [db [_ id err op]]
   (log/warn "Failed to invoke" op ":" err)
   (-> db
       (db/set-alerts [{:type :danger
                        :message (str "Could not load details for customer " id ": "
                                      (u/error-msg err))}])
       (db/unset-loading))))
