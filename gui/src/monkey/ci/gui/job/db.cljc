(ns monkey.ci.gui.job.db)

(def alerts ::alerts)

(defn set-alerts
  ([db path a]
   (assoc-in db [alerts path] a))
  ([db a]
   (set-alerts db :global a)))

(defn clear-alerts
  ([db path]
   (update db alerts dissoc path))
  ([db]
   (clear-alerts db :global)))

(defn path-alerts [db path]
  (get-in db [alerts path]))

(defn global-alerts [db]
  (path-alerts db :global))

(defn logs [db path]
  (get-in db [::logs path]))

(defn set-logs [db path l]
  (assoc-in db [::logs path] l))

(def log-files ::log-files)

(defn set-log-files [db l]
  (assoc db log-files l))
