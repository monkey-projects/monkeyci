(ns monkey.ci.gui.params.events
  (:require [medley.core :as mc]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.params.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :params/load
 (fn [{:keys [db]} [_ cust-id]]
   {:db (-> db
            (db/mark-loading)
            (db/clear-alerts)
            (db/clear-editing))
    :dispatch [:secure-request
               :get-customer-params
               {:customer-id cust-id}
               [:params/load--success]
               [:params/load--failed]]}))

(rf/reg-event-db
 :params/load--success
 (fn [db [_ {params :body}]]
   (-> db
       (db/set-params params)
       (db/unmark-loading))))

(rf/reg-event-db
 :params/load--failed
 (fn [db [_ err]]
   (-> db
       (db/set-alerts [{:type :danger
                        :message (str "Failed to load customer params: " (u/error-msg err))}])
       (db/unmark-loading))))

(def new-set? (comp (some-fn nil? db/temp-id?) :id))

(rf/reg-event-db
 :params/new-set
 (fn [db _]
   (let [id (db/new-temp-id)]
     (db/set-editing db id {:id id
                            :parameters []
                            :label-filters []}))))

(rf/reg-event-db
 :params/edit-set
 (fn [db [_ id]]
   (let [ps (->> (db/params db)
                 (filter (comp (partial = id) :id))
                 (first))]
     (db/set-editing db id ps))))

(rf/reg-event-db
 :params/cancel-set
 (fn [db [_ id]]
   (db/unset-editing db id)))

(rf/reg-event-fx
 :params/delete-set
 (fn [{:keys [db]} [_ id]]
   {:dispatch [:secure-request
               :delete-param-set
               {:customer-id (r/customer-id db)
                :param-id id}
               [:params/delete-set--success id]
               [:params/delete-set--failed id]]
    :db (db/mark-set-deleting db id)}))

(rf/reg-event-db
 :params/delete-set--success
 (fn [db [_ id]]
   (-> db
       (db/unset-editing id)
       (db/unmark-set-deleting id))))

(rf/reg-event-db
 :params/delete-set--failed
 (fn [db [_ id err]]
   (-> db
       (db/set-set-alerts id
                          [{:type :danger
                            :message (str "Failed to delete parameter set: " (u/error-msg err))}])
       (db/unmark-set-deleting id))))

(rf/reg-event-db
 :params/new-param
 (fn [db [_ id]]
   (db/update-editing db id update :parameters concat [{}])))

(rf/reg-event-db
 :params/delete-param
 (fn [db [_ set-id param-idx]]
   (db/update-editing db set-id update :parameters (comp vec (partial mc/remove-nth param-idx)))))

(rf/reg-event-fx
 :params/cancel-all
 (fn [{:keys [db]} _]
   {:db (db/clear-editing db)
    :dispatch [:route/goto :page/customer {:customer-id (r/customer-id db)}]}))

(rf/reg-event-db
 :params/description-changed
 (fn [db [_ set-id desc]]
   (db/update-editing db set-id assoc :description desc)))

(rf/reg-event-db
 :params/label-changed
 (fn [db [_ set-id param-idx new-val]]
   (db/update-editing-param db set-id param-idx assoc :name new-val)))

(rf/reg-event-db
 :params/value-changed
 (fn [db [_ set-id param-idx new-val]]
   (db/update-editing-param db set-id param-idx assoc :value new-val)))

(rf/reg-event-fx
 :params/save-set
 (fn [{:keys [db]} [_ id]]
   (let [new? (db/temp-id? id)]
     {:dispatch [:secure-request
                 (if new? :create-param-set :update-param-set)
                 (cond-> {:customer-id (r/customer-id db)
                          :params (db/get-editing db id)}
                   (not new?) (assoc :param-id id)
                   new? (update :params dissoc :id))
                 [:params/save-set--success id]
                 [:params/save-set--failed id]]
      :db (-> db
              (db/mark-saving id)
              (db/clear-set-alerts id))})))

(rf/reg-event-db
 :params/save-set--success
 (fn [db [_ id {params :body}]]
   (letfn [(replace-or-add [existing]
             (if-let [match (->> existing
                                 (filter (comp (partial = id) :id))
                                 (first))]
               (replace {match params} existing)
               (conj (vec existing) params)))]
     (-> db
         (db/unmark-saving id)
         (db/unset-editing id)
         (db/set-params (->> (db/params db)
                             (replace-or-add)))))))

(rf/reg-event-db
 :params/save-set--failed
 (fn [db [_ id err]]
   (-> db
       (db/unmark-saving id)
       (db/set-set-alerts id [{:type :danger
                               :message (str "Failed to save parameter set: " (u/error-msg err))}]))))
