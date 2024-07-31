(ns monkey.ci.gui.repo.db)

(def id ::repo)

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

(def show-trigger-form? ::show-trigger-form?)

(defn set-show-trigger-form [db f]
  (assoc db show-trigger-form? f))

(def triggering? ::triggering?)

(defn set-triggering [db]
  (assoc db triggering? true))

(defn unset-triggering [db]
  (dissoc db triggering?))

(def editing ::editing)

(defn set-editing [db e]
  (assoc db editing e))

(defn update-editing [db f & args]
  (apply update db editing f args))

(defn update-labels [db f & args]
  (apply update-in db [editing :labels] f args))

(defn update-label [db lbl f & args]
  (update-labels db (partial replace {lbl (apply f lbl args)})))

(def edit-alerts ::edit-alerts)

(defn set-edit-alerts [db a]
  (assoc db edit-alerts a))

(defn reset-edit-alerts [db]
  (dissoc db edit-alerts))

(def saving? ::saving?)

(defn set-saving [db s]
  (assoc db saving? s))

(defn mark-saving [db]
  (set-saving db true))

(defn unmark-saving [db]
  (dissoc db saving?))
