(ns monkey.ci.storage.sql.webhook
  (:require [clojure.spec.alpha :as spec]
            [monkey.ci.entities
             [core :as ec]
             [webhook :as ewh]]
            [monkey.ci.storage.sql
             [common :as sc]
             [repo :as sr]]))

(defn- webhook->db [wh repo-id]
  (-> wh
      (dissoc :id :repo-id :org-id :secret-key)
      (assoc :cuid (:id wh)
             :repo-id repo-id
             :secret (:secret-key wh))))

(defn- insert-webhook [conn wh repo-id]
  (ec/insert-webhook conn (webhook->db wh repo-id)))

(defn- update-webhook [conn wh existing repo-id]
  (ec/update-webhook conn (merge existing (webhook->db wh repo-id))))

(defn upsert-webhook [conn wh]
  (spec/valid? :entity/webhook wh)
  (if-let [repo-id (sr/select-repo-id-by-sid conn [(:org-id wh) (:repo-id wh)])]
    (if-let [existing (ec/select-webhook conn (ec/by-cuid (:id wh)))]
      (update-webhook conn wh existing repo-id)
      (insert-webhook conn wh repo-id))
    (throw (ex-info "Repository does not exist" wh))))

(defn select-webhook [conn cuid]
  (-> (ewh/select-webhooks-as-entity conn (ewh/by-cuid cuid))
      (first)))

(defn select-repo-webhooks [st [org-id repo-id]]
  (ewh/select-webhooks-as-entity (sc/get-conn st) (ewh/by-repo org-id repo-id)))

(defn delete-webhook [conn cuid]
  (ec/delete-webhooks conn (ec/by-cuid cuid)))
