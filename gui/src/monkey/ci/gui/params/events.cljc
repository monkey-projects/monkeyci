(ns monkey.ci.gui.params.events
  (:require [medley.core :as mc]
            [monkey.ci.gui.labels :as lbl]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.params.db :as db]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn labels-id [id]
  [:params id])

(rf/reg-event-fx
 :params/load
 (fn [{:keys [db]} [_ org-id]]
   {:db (-> db
            (db/mark-loading)
            (db/clear-alerts)
            (db/clear-editing))
    :dispatch [:secure-request
               :get-org-params
               {:org-id org-id}
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
                        :message (str "Failed to load organization params: " (u/error-msg err))}])
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
     (-> db
         (db/set-editing id ps)
         (lbl/set-labels (labels-id id) (:label-filters ps))))))

(rf/reg-event-db
 :params/cancel-set
 (fn [db [_ id]]
   (-> db
       (db/unset-editing id)
       (lbl/clear-labels (labels-id id)))))

(rf/reg-event-fx
 :params/delete-set
 (fn [{:keys [db]} [_ id]]
   {:dispatch [:secure-request
               :delete-param-set
               {:org-id (r/org-id db)
                :param-id id}
               [:params/delete-set--success id]
               [:params/delete-set--failed id]]
    :db (db/mark-set-deleting db id)}))

(rf/reg-event-db
 :params/delete-set--success
 (fn [db [_ id]]
   (-> db
       (db/unset-editing id)
       (db/unmark-set-deleting id)
       (db/set-params (->> (db/params db)
                           (remove (comp (partial = id) :id))
                           (vec)))
       (lbl/clear-labels (labels-id id)))))

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
    :dispatch [:route/goto :page/org {:org-id (r/org-id db)}]}))

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
   (let [new? (db/temp-id? id)
         lbls (lbl/get-labels db (labels-id id))
         param (-> (db/get-editing db id)
                   (assoc :label-filters (or lbls [])))]
     (log/debug "Saving parameter set:" (str param))
     {:dispatch [:secure-request
                 (if new? :create-param-set :update-param-set)
                 (cond-> {:org-id (r/org-id db)
                          :params param}
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
