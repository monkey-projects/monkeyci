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
