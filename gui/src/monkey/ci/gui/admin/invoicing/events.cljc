(ns monkey.ci.gui.admin.invoicing.events
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.admin.invoicing.db :as db]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::load
 (lo/loader-evt-handler
  db/id
  (fn [_ _ [_ org-id]]
    [:secure-request
     :get-org-invoices
     {:org-id org-id}
     [::load--success]
     [::load--failure]])))

(rf/reg-event-db
 ::load--success
 (fn [db [_ resp]]
   (lo/on-success db db/id resp)))

(rf/reg-event-db
 ::load--failure
 (fn [db [_ resp]]
   (lo/on-failure db db/id a/invoice-load-failed resp)))
