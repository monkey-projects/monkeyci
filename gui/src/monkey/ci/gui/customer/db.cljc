(ns monkey.ci.gui.customer.db)

(def loading? ::loading?)

(defn set-loading [db]
  (assoc db loading? true))

(defn unset-loading [db]
  (dissoc db loading?))

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

(defn set-alerts [db a]
  (assoc db alerts a))

(defn reset-alerts [db]
  (dissoc db alerts))

(def repo-alerts ::repo-alerts)

(defn set-repo-alerts [db a]
  (assoc db repo-alerts a))

(defn reset-repo-alerts [db]
  (dissoc db repo-alerts))

(def github-repos ::github-repos)

(defn set-github-repos [db r]
  (assoc db github-repos r))
