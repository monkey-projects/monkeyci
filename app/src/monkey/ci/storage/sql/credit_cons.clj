(ns monkey.ci.storage.sql.credit-cons
  (:require [medley.core :as mc]
            [monkey.ci.entities
             [build :as eb]
             [core :as ec]
             [credit-cons :as eccon]]
            [monkey.ci.storage.sql.common :as sc]))

(def build-sid (juxt :org-id :repo-id :build-id))

(defn- credit-cons->db [cc]
  (-> (sc/id->cuid cc)
      (dissoc :org-id :repo-id)))

(defn- db->credit-cons [cc]
  (mc/filter-vals some? cc))

(defn- insert-credit-consumption [conn cc]
  (let [build (apply eb/select-build-by-sid conn (build-sid cc))
        credit (ec/select-org-credit conn (ec/by-cuid (:credit-id cc)))]
    (when-not build
      (throw (ex-info "Build not found" cc)))
    (when-not credit
      (throw (ex-info "Org credit not found" cc)))
    (ec/insert-credit-consumption conn (assoc (credit-cons->db cc)
                                              :build-id (:id build)
                                              :credit-id (:id credit)))))

(defn- update-credit-consumption [conn cc existing]
  (ec/update-credit-consumption conn (merge existing
                                            (-> (credit-cons->db cc)
                                                (dissoc :build-id :credit-id)))))

(defn upsert-credit-consumption [conn cc]
  ;; TODO Update available-credits table
  (if-let [existing (ec/select-credit-consumption conn (ec/by-cuid (:id cc)))]
    (update-credit-consumption conn cc existing)
    (insert-credit-consumption conn cc)))

(defn select-credit-consumption [conn cuid]
  (some->> (eccon/select-credit-cons conn (eccon/by-cuid cuid))
           (first)
           (db->credit-cons)))

(defn select-org-credit-cons [st org-id]
  (->> (eccon/select-credit-cons (sc/get-conn st) (eccon/by-org org-id))
       (map db->credit-cons)))

(defn select-org-credit-cons-since [st org-id since]
  (->> (eccon/select-credit-cons (sc/get-conn st) (eccon/by-org-since org-id since))
       (map db->credit-cons)))
