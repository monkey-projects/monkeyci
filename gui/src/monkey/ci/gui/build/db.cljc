(ns monkey.ci.gui.build.db)

(def initialized? ::initialized)

(defn set-initialized [db v]
  (assoc db initialized? v))

(defn unset-initialized [db]
  (dissoc db initialized?))

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn reset-alerts [db]
  (dissoc db alerts))

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
   (set-reloading db #{:build})))

(defn clear-reloading [db r]
  (update db reloading disj r))

(defn clear-build-reloading [db]
  (clear-reloading db :build))

(defn reloading? [db]
  (not-empty (reloading db)))

(def downloading? ::downloading?)

(defn mark-downloading [db]
  (assoc db downloading? true))

(defn reset-downloading [db]
  (dissoc db downloading?))

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

(def expanded-jobs ::expanded-jobs)

(defn set-expanded-jobs [db ids]
  (assoc db expanded-jobs (set ids)))

(defn toggle-expanded-job [db id]
  (update db expanded-jobs (fnil (fn [ids]
                                   (let [toggle (if (ids id) disj conj)]
                                     (toggle ids id)))
                                 #{})))
