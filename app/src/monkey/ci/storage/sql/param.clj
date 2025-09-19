(ns monkey.ci.storage.sql.param
  (:require [clojure.spec.alpha :as spec]
            [clojure.tools.logging :as log]
            [monkey.ci
             [labels :as lbl]
             [storage :as st]]
            [monkey.ci.entities
             [core :as ec]
             [param :as eparam]]
            [monkey.ci.spec.entities]
            [monkey.ci.storage.sql.common :as sc]))

(defn- insert-param-values [conn values param-id]
  (when-not (empty? values)
    (->> values
         (map (fn [v]
                (-> (select-keys v [:name :value])
                    (assoc :params-id param-id))))
         (ec/insert-org-param-values conn))))

(defn- update-param-values [conn values]
  (doseq [pv values]
    (ec/update-org-param-value conn pv)))

(defn- delete-param-values [conn values]
  (when-not (empty? values)
    (ec/delete-org-param-values conn [:in :id (map :id values)])))

(defn- param->db [param org-id]
  (-> param
      (sc/id->cuid)
      (select-keys [:cuid :description :label-filters :dek])
      (assoc :org-id org-id)))

(defn- insert-param [conn param org-id]
  (let [{:keys [id]} (ec/insert-org-param conn (param->db param org-id))]
    (insert-param-values conn (:parameters param) id)))

(defn- update-param [conn param org-id existing]
  (ec/update-org-param conn (merge existing (param->db param org-id)))
  (let [ex-vals (ec/select-org-param-values conn (ec/by-params (:id existing)))
        r (lbl/reconcile-labels ex-vals (:parameters param))]
    (log/debug "Reconciled param values:" r)
    (insert-param-values conn (:insert r) (:id existing))
    (update-param-values conn (:update r))
    (delete-param-values conn (:delete r))))

(defn- upsert-param [conn param org-id]
  (spec/valid? :entity/org-params param)
  (if-let [existing (ec/select-org-param conn (ec/by-cuid (:id param)))]
    (update-param conn param org-id existing)
    (insert-param conn param org-id)))

(defn upsert-params [conn org-cuid params]
  (when-not (empty? params)
    (let [{org-id :id} (ec/select-org conn (ec/by-cuid org-cuid))]
      (doseq [p params]
        (upsert-param conn p org-id))
      params)))

(defn select-params [conn org-id]
  ;; Select org params and values for org cuid
  (eparam/select-org-params-with-values conn org-id))

(defn upsert-org-param [st {:keys [org-id] :as param}]
  (let [conn (sc/get-conn st)]
    (when-let [{db-id :id} (ec/select-org conn (ec/by-cuid org-id))]
      (upsert-param conn param db-id)
      (st/params-sid org-id (:id param)))))

(defn select-org-param [st [_ _ param-id]]
  (eparam/select-param-with-values (sc/get-conn st) param-id))

(defn delete-org-param [st [_ _ param-id]]
  (pos? (ec/delete-org-params (sc/get-conn st) (ec/by-cuid param-id))))
