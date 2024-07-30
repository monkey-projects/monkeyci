(ns monkey.ci.gui.customer.db)

(defn loading?
  ([db]
   (::loading? db))
  ([db id]
   (true? (get-in db [id :loading?]))))

(defn set-loading
  ([db]
   (assoc db ::loading? true))
  ([db id]
   (assoc-in db [id :loading?] true)))

(defn unset-loading
  ([db]
   (dissoc db ::loading?))
  ([db id]
   (update db id dissoc :loading?)))

(defn set-value [db id d]
  (assoc-in db [id :value] d))

(defn get-value [db id]
  (get-in db [id :value]))

(def customer ::customer)

(defn set-customer [db i]
  (assoc db customer i))

(defn update-customer [db f & args]
  (apply update db customer f args))

(defn replace-repo
  "Updates customer repos by replacing the existing repo with the same id."
  [db updated-repo]
  (letfn [(replace-with [repos]
            (if-let [match (->> repos
                                (filter (comp (partial = (:id updated-repo)) :id))
                                (first))]
              (replace {match updated-repo} repos)
              (conj repos updated-repo)))]
    (update-customer db update :repos replace-with)))

(def alerts ::alerts)

(defn set-alerts
  ([db a]
   (assoc db alerts a))
  ([db id a]
   (assoc-in db [id :alerts] a)))

(defn get-alerts [db id]
  (get-in db [id :alerts]))

(defn reset-alerts
  ([db]
   (dissoc db alerts))
  ([db id]
   (update db id dissoc :alerts)))

(def repo-alerts ::repo-alerts)

(defn set-repo-alerts [db a]
  (assoc db repo-alerts a))

(defn reset-repo-alerts [db]
  (dissoc db repo-alerts))

(def github-repos ::github-repos)

(defn set-github-repos [db r]
  (assoc db github-repos r))

(def customer-creating? ::customer-creating)

(defn mark-customer-creating [db]
  (assoc db customer-creating? true))

(defn unmark-customer-creating [db]
  (dissoc db customer-creating?))

(def create-alerts ::create-alerts)

(defn set-create-alerts [db a]
  (assoc db create-alerts a))

(defn reset-create-alerts [db]
  (dissoc db create-alerts))

(def latest-builds ::latest-builds)

(defn get-latest-builds [db]
  (get-value db latest-builds))

(defn set-latest-builds [db b]
  (set-value db latest-builds b))
