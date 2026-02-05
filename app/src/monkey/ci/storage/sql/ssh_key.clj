(ns monkey.ci.storage.sql.ssh-key
  (:require [clojure.set :as cs]
            [clojure.spec.alpha :as spec]
            [clojure.tools.logging :as log]
            [monkey.ci.entities
             [core :as ec]
             [ssh-key :as essh]]
            [monkey.ci.storage.spec :as ss]
            [monkey.ci.storage.sql.common :as sc]))

(defn- ssh-key->db [k]
  (-> k
      (sc/id->cuid)
      (dissoc :org-id)))

(defn- insert-ssh-key [conn ssh-key org-id]
  (log/debug "Inserting ssh key:" ssh-key)
  (ec/insert-ssh-key conn (-> ssh-key
                              (ssh-key->db)
                              (assoc :org-id org-id))))

(defn- update-ssh-key [conn ssh-key existing]
  (log/debug "Updating ssh key:" ssh-key)
  (ec/update-ssh-key conn (merge existing (ssh-key->db ssh-key))))

(defn- upsert-ssh-key [conn org-id ssh-key]
  (spec/valid? ::ss/ssh-key ssh-key)
  (if-let [existing (ec/select-ssh-key conn (ec/by-cuid (:id ssh-key)))]
    (update-ssh-key conn ssh-key existing)
    (insert-ssh-key conn ssh-key org-id)))

(defn upsert-ssh-keys [conn org-cuid ssh-keys]
  (if-let [{org-id :id} (ec/select-org conn (ec/by-cuid org-cuid))]
    (let [to-delete (-> (ec/select-ssh-keys conn (ec/by-org org-id))
                        (as-> k (map :cuid k))
                        (set)
                        (cs/difference (->> ssh-keys
                                            (map :id)
                                            (remove nil?)
                                            (set))))]
      (doseq [k ssh-keys]
        (upsert-ssh-key conn org-id k))
      (when-not (empty? to-delete)
        (log/debug "Deleting ssh keys:" to-delete)
        (ec/delete-ssh-keys conn [:in :cuid to-delete])))
    (throw (ex-info "Org not found when upserting ssh keys" {:org-id org-cuid})))
  ssh-keys)

(defn select-ssh-keys [conn org-id]
  (essh/select-ssh-keys-as-entity conn org-id))
