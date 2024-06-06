(ns monkey.ci.gui.home.db)

(def customers ::customers)

(defn set-customers [db c]
  (assoc db customers c))

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn clear-alerts [db]
  (dissoc db alerts))

(def customer-searching? ::customer-searching?)

(defn set-customer-searching [db v]
  (assoc db customer-searching? v))

(defn reset-customer-searching [db]
  (dissoc db customer-searching?))

(def join-alerts ::join-alerts)

(defn set-join-alerts [db a]
  (assoc db join-alerts a))

(defn clear-join-alerts [db]
  (dissoc db join-alerts))

(def search-results ::search-results)

(defn set-search-results [db r]
  (assoc db search-results r))
