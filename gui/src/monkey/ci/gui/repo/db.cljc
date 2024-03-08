(ns monkey.ci.gui.repo.db)

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(defn reset-alerts [db]
  (dissoc db alerts))

(def builds ::builds)

(defn set-builds [db b]
  (assoc db builds b))

(def build-uid (juxt :customer-id :repo-id :build-id))

(defn find-build [db b]
  (->> (builds db)
       (filter (comp (partial = (build-uid b)) build-uid))
       (first)))

(defn update-build
  "Replaces build in the list, or adds it if not present yet"
  [db b]
  (if-let [m (find-build db b)]
    (update db builds (partial replace {m b}))
    (update db builds conj b)))

(def latest-build ::latest-build)

(defn set-latest-build [db b]
  (assoc db latest-build b))
