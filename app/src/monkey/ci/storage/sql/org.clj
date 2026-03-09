(ns monkey.ci.storage.sql.org
  (:require [clojure.spec.alpha :as spec]
            [medley.core :as mc]
            [monkey.ci
             [storage :as st]
             [utils :as u]]
            [monkey.ci.entities
             [core :as ec]
             [org :as ecu]
             [spec :as es]]
            [monkey.ci.storage.spec :as ss]
            [monkey.ci.storage.sql
             [common :as sc]
             [repo :as sr]]))

(defn- org->db [org]
  (-> org
      (sc/id->cuid)
      (select-keys [:cuid :name :display-id])))

(def db->org sc/cuid->id)

(defn db->org-with-repos [c]
  (letfn [(entities->repos [repos]
            (reduce-kv (fn [r _ v]
                         (assoc r (:display-id v) (sr/db->repo v)))
                       {}
                       repos))]
    (-> c
        (db->org)
        (mc/update-existing :repos entities->repos))))

(defn- insert-org [conn org]
  (let [org-id (:id (ec/insert-org conn (org->db org)))]
    (sr/upsert-repos conn org org-id)
    org))

(defn- update-org [conn org existing]
  (let [ce (org->db org)]
    (spec/valid? ::es/org ce)
    (when (not= ce existing)
      (ec/update-org conn (merge existing ce)))
    (sr/upsert-repos conn org (:id existing))
    org))

(defn upsert-org [conn org]
  (spec/valid? ::ss/org org)
  (if-let [existing (ec/select-org conn (ec/by-cuid (:id org)))]
    (update-org conn org existing)
    (insert-org conn org)))

(defn- select-org-display-ids [conn]
  (ecu/org-display-ids conn))

(defn init-org [st {:keys [org] :as opts}]
  (let [conn (sc/get-conn st)
        existing? (select-org-display-ids conn)
        org (ec/insert-org conn (-> org
                                    (assoc :display-id (u/name->display-id (:name org) existing?))
                                    (org->db)))
        org-id (:id org)]
    (when-let [uid (some->> (:user-id opts)
                            (ec/by-cuid)
                            (ec/select-users conn)
                            first
                            :id)]
      (ec/insert-user-orgs conn uid [org-id]))
    (doseq [{:keys [amount from until period] :as conf} (:credits opts)]
      (let [cse (-> conf
                    (dissoc :from :until :period)
                    (assoc :cuid (st/new-id)
                           :org-id org-id
                           :valid-from from
                           :valid-until until
                           :valid-period period))]
        (when-let [cs (ec/insert-credit-subscription conn cse)]
          (ec/insert-org-credit conn {:cuid (st/new-id)
                                      :org-id org-id
                                      :amount amount
                                      :valid-from from
                                      :type :subscription
                                      :subscription-id (:id cs)}))))
    (when-let [dek (:dek opts)]
      (ec/insert-crypto conn {:dek dek :org-id org-id}))
    (st/org-sid (:cuid org))))

(defn- select-org-by-filter [conn f]
  (some-> (ecu/org-with-repos conn f)
          (db->org-with-repos)))

(defn select-org [conn cuid]
  (when cuid
    (select-org-by-filter conn (ec/by-cuid cuid))))

(defn select-org-by-display-id [st did]
  (when did
    (select-org-by-filter (sc/get-conn st) (ec/by-display-id did))))

(defn select-org-id-by-display-id [st did]
  (when did
    (ecu/org-id-by-display-id (sc/get-conn st) did)))

(defn org-exists? [conn cuid]
  (some? (ec/select-org conn (ec/by-cuid cuid))))

(defn delete-org [conn cuid]
  (when cuid
    (ec/delete-orgs conn (ec/by-cuid cuid))))

(defn select-orgs
  "Finds orgs by filter"
  [st {:keys [id name]}]
  (let [query (cond
                id (ec/by-cuid id)
                ;; By default, this will use case insensitive search (depends on collation)
                name [:like :name (str "%" name "%")])]
    (->> (ec/select-orgs (sc/get-conn st) query)
         (map db->org-with-repos))))

(defn select-orgs-by-id [st ids]
  (when (not-empty ids)
    (->> (ec/select-orgs (sc/get-conn st) [:in :cuid (distinct ids)])
         (map db->org))))

(defn count-orgs [st]
  (ec/count-entities (sc/get-conn st) :orgs))
