(ns monkey.ci.gui.customer.db)

(def loading? ::loading?)

(defn set-loading [db]
  (assoc db loading? true))

(defn unset-loading [db]
  (dissoc db loading?))

(def customer ::customer)

(defn set-customer [db i]
  (assoc db customer i))

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn reset-alerts [db]
  (dissoc db alerts))
