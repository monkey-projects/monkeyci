(ns monkey.ci.entities.email-confirmation
  (:require [monkey.ci.entities.core :as ec]))

(def base-query
  {:select [:c.* [:r.cuid :reg-cuid]]
   :from [[:email-confirmations :c]]
   :join [[:email-registrations :r] [:= :r.id :c.email-reg-id]]})

(defn select-email-confirmation [conn cuid]
  (some->> (assoc base-query :where [:= :c.cuid cuid])
           (ec/select conn)
           (first)
           (ec/convert-email-conf-select)))

(defn select-email-confirmations-by-reg [conn reg-id]
  (->> (assoc base-query :where [:= :r.cuid reg-id])
       (ec/select conn)
       (map ec/convert-email-conf-select)))
