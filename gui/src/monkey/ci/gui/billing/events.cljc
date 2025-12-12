(ns monkey.ci.gui.billing.events
  (:require [clojure.string :as cs]
            [medley.core :as mc]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.billing.db :as db]
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

(defn- split-address-lines [v]
  (assoc v :address-lines (->> (concat (cs/split-lines (:address v)) (repeat ""))
                               (take 3)
                               (vec))))

(rf/reg-event-db
 ::load-invoicing--success
 (fn [db [_ resp]]
   (-> (lo/on-success db db/billing-id resp)
       (db/update-invoicing-settings split-address-lines))))

(rf/reg-event-db
 ::load-invoicing--failure
 (fn [db [_ err]]
   (lo/on-failure db db/billing-id a/invoice-settings-load-failed err)))

(rf/reg-event-db
 ::invoicing-settings-changed
 (fn [db [_ prop v]]
   (db/update-invoicing-settings db assoc prop v)))

(rf/reg-event-db
 ::invoicing-address-changed
 (fn [db [_ idx v]]
   (db/update-invoicing-settings db update :address-lines (partial mc/replace-nth idx v))))
