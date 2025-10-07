(ns monkey.ci.gui.ssh-keys.events
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.labels :as lbl]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.ssh-keys.db :as db]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :ssh-keys/initialize
 (fn [{:keys [db]} [_ org-id]]
   (lo/on-initialize db db/id
                     {:init-events [[:ssh-keys/load org-id]]})))

(rf/reg-event-fx
 :ssh-keys/load
 (lo/loader-evt-handler
  db/id
  (fn [_ _ [_ org-id]]
    [:secure-request
     :get-org-ssh-keys
     {:org-id org-id}
     [:ssh-keys/load--success]
     [:ssh-keys/load--failed]])))

(rf/reg-event-db
 :ssh-keys/load--success
 (fn [db [_ resp]]
   (lo/on-success db db/id resp)))

(rf/reg-event-db
 :ssh-keys/load--failed
 (fn [db [_ err]]
   (lo/on-failure db db/id a/org-ssh-keys-failed err)))

(rf/reg-event-db
 :ssh-keys/new
 (fn [db _]
   (db/update-editing-keys db (comp vec conj) {:temp-id (random-uuid)})))

(defn labels-id [id]
  [db/id id])

(def set-id db/set-id)
(def new-set? db/new-set?)

(rf/reg-event-db
 :ssh-keys/cancel-set
 (fn [db [_ ks]]
   (db/update-editing-keys db (partial remove (db/same-id? (db/set-id ks))))))

(rf/reg-event-db
 :ssh-keys/prop-changed
 (fn [db [_ ks prop val]]
   (db/update-editing-key db (set-id ks) assoc prop val)))

(defn- ->api [ks]
  (-> ks
      (dissoc :editing? :temp-id)
      (update :label-filters #(or % []))))

(defn- add-filters [db ks]
  (assoc ks :label-filters (lbl/get-labels db (labels-id (set-id ks)))))

(rf/reg-event-fx
 :ssh-keys/save-set
 (fn [{:keys [db]} [_ ks]]
   (let [org-id (r/org-id db)
         all (db/get-value db)
         orig (->> all
                   (filter (db/same-id? (set-id ks)))
                   (first))
         ks (add-filters db ks)]
     {:dispatch [:secure-request
                 :update-org-ssh-keys
                 {:org-id org-id
                  :ssh-keys (cond->> all
                              orig (replace {orig ks})
                              (not orig) (concat [ks])
                              true (map ->api))
                  :headers {:content-type "application/edn"}}
                 [:ssh-keys/save-set--success ks]
                 [:ssh-keys/save-set--failed ks]]
      :db (db/reset-alerts db)})))

(rf/reg-event-db
 :ssh-keys/save-set--success
 (fn [db [_ ks {:keys [body]}]]
   (-> db
       (db/update-editing-keys (partial remove (db/same-id? (set-id ks))))
       (db/set-value body))))

(rf/reg-event-db
 :ssh-keys/save-set--failed
 (fn [db [_ _ err]]
   (db/set-alerts db [(a/org-save-ssh-keys-failed err)])))

(rf/reg-event-db
 :ssh-keys/edit-set
 (fn [db [_ ks]]
   (db/update-editing-keys db (comp vec conj) ks)))

(rf/reg-event-fx
 :ssh-keys/delete-set
 (fn [{:keys [db]} [_ ks]]
   (let [org-id (r/org-id db)
         all (db/get-value db)]
     {:dispatch [:secure-request
                 :update-org-ssh-keys
                 {:org-id org-id
                  :ssh-keys (remove (db/same-id? (set-id ks)) all)}
                 [:ssh-keys/save-set--success ks]
                 [:ssh-keys/save-set--failed ks]]})))
