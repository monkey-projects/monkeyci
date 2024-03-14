(ns monkey.ci.gui.build.db)

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn reset-alerts [db]
  (dissoc db alerts))

(def logs ::logs)

(defn set-logs [db l]
  (assoc db logs l))

(def build ::build)

(defn set-build [db l]
  (assoc db build l))

(defn update-build [db f & args]
  (apply update db build f args))

(def reloading ::reloading)

(defn set-reloading
  "Sets the set of reloading things"
  ([db r]
   (assoc db reloading r))
  ([db]
   (set-reloading db #{:build :logs})))

(defn clear-reloading [db r]
  (update db reloading disj r))

(defn clear-build-reloading [db]
  (clear-reloading db :build))

(defn clear-logs-reloading [db]
  (clear-reloading db :logs))

(defn reloading? [db]
  (not-empty (reloading db)))

(def downloading? ::downloading?)

(defn mark-downloading [db]
  (assoc db downloading? true))

(defn reset-downloading [db]
  (dissoc db downloading?))

(def current-log ::current-log)

(defn set-current-log [db l]
  (assoc db current-log l))

(def log-path ::log-path)

(defn set-log-path [db p]
  (assoc db log-path p))

(def log-alerts ::log-alerts)

(defn set-log-alerts [db a]
  (assoc db log-alerts a))

(defn reset-log-alerts [db]
  (dissoc db log-alerts))

(def auto-reload? ::auto-reload)

(defn set-auto-reload [db v]
  (assoc db auto-reload? v))

(def last-reload-time ::last-reload-time)

(defn set-last-reload-time [db t]
  (assoc db last-reload-time t))
