(ns monkey.ci.gui.billing.events
  (:require [monkey.ci.gui.billing.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 ::load-invoicing
 (lo/loader-evt-handler
  db/billing-id
  (fn [_ {:keys [db]} _]
    [:secure-request
     :get-org-invoicing-settings
     {:org-id (r/org-id db)}
     [::load-invoicing--success]
     [::load-invoicing--failure]])))

(rf/reg-event-db
 ::load-invoicing--success
 (fn [db [_ {:keys [body]}]]
   ;; TODO
   ))

(rf/reg-event-db
 ::load-invoicing--failure
 (fn [db [_ err]]
   ;; TODO
   ))
