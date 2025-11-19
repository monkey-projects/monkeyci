(ns monkey.ci.gui.api-keys.events
  (:require [medley.core :as mc]
            [monkey.ci.gui.api-keys.db :as db]
            [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.time :as t]
            [re-frame.core :as rf]))

(def config
  {db/org-id {:request :get-org-tokens
              :save-request :create-org-token
              :delete-request :delete-org-token}})

(rf/reg-event-fx
 :tokens/load
 (fn [{:keys [db]} [_ id opts]]
   {:dispatch [:secure-request
               (get-in config [id :request])
               opts
               [:tokens/load--success id]
               [:tokens/load--failed id]]
    :db (lo/before-request db id)}))

(rf/reg-event-db
 :tokens/load--success
 (fn [db [_ id v]]
   (lo/on-success db id v)))

(rf/reg-event-db
 :tokens/load--failed
 (fn [db [_ id err]]
   (lo/on-failure db id a/tokens-load-failed err)))

(rf/reg-event-db
 :tokens/new
 (fn [db [_ id]]
   (-> db
       (db/set-token-edit id {})
       (db/reset-new-token id))))

(rf/reg-event-db
 :tokens/cancel-edit
 (fn [db [_ id]]
   (-> db
       (db/reset-token-edit id)
       (db/reset-new-token id))))

(rf/reg-event-db
 :tokens/edit-changed
 (fn [db [_ id prop v]]
   (db/update-token-edit db id assoc prop v)))

(rf/reg-event-fx
 :tokens/save
 (fn [{:keys [db]} [_ id]]
   {:dispatch [:secure-request
               (get-in config [id :save-request])
               (assoc (r/path-params (r/current db))
                      :token (-> (db/get-token-edit db id)
                                 (mc/update-existing :valid-until (comp t/to-epoch t/parse-iso))))
               [:tokens/save--success id]
               [:tokens/save--failed id]]
    :db (db/set-saving db id)}))

(rf/reg-event-db
 :tokens/save--success
 (fn [db [_ id {:keys [body]}]]
   (-> db
       (db/set-new-token id body)
       (db/reset-saving id)
       (db/update-tokens id (comp vec conj) (dissoc body :token))
       (db/reset-token-edit id))))

(rf/reg-event-db
 :tokens/save--failed
 (fn [db [_ id err]]
   (-> db
       (db/set-alerts id [(a/token-create-failed err)])
       (db/reset-saving id))))

(rf/reg-event-db
 :tokens/prepare-delete
 (fn [db [_ id token-id]]
   (db/set-token-to-delete db id token-id)))

(rf/reg-event-fx
 :tokens/delete
 (fn [{:keys [db]} [_ id]]
   (let [token-id (db/get-token-to-delete db id)]
     {:dispatch [:secure-request
                 (get-in config [id :delete-request])
                 (-> (r/current db)
                     (r/path-params)
                     (assoc :token-id token-id))
                 [:tokens/delete--success id token-id]
                 [:tokens/delete--failed id token-id]]
      :db (db/set-deleting db id)})))

(rf/reg-event-db
 :tokens/delete--success
 (fn [db [_ id token-id]]
   (-> db
       (db/update-tokens id (comp vec (partial remove (comp (partial = token-id) :id))))
       (db/reset-token-to-delete id)
       (db/reset-deleting id))))

(rf/reg-event-db
 :tokens/delete--failed
 (fn [db [_ id token-id err]]
   (-> db
       (db/set-alerts id [(a/token-delete-failed err)])
       (db/reset-token-to-delete id)
       (db/reset-deleting id))))
