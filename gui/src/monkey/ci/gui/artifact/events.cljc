(ns monkey.ci.gui.artifact.events
  (:require #?@(:node []
                :cljs [[ajax.core :as ajax]])
            [monkey.ci.gui.artifact.db :as db]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.martian :as m]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def format #?@(:node []
                :cljs [(assoc (ajax/raw-response-format) :type :blob)]))

(rf/reg-event-fx
 :artifact/download
 (fn [{:keys [db]} [_ art-id]]
   (let [{:keys [customer-id repo-id build-id]} (-> (r/current db)
                                                    (r/path-params))
         token (ldb/token db)]
     {:http-xhrio {:method :get
                   :uri (m/api-url (str "/customer/" customer-id
                                        "/repo/" repo-id
                                        "/builds/" build-id
                                        "/artifact/" art-id
                                        "/download"))
                   :headers {"Authorization" (str "Bearer " token)}
                   :response-format format
                   :on-success [:artifact/download--success art-id]
                   :on-failure [:artifact/download--failed art-id]}
      :db (-> db
              (db/set-downloading art-id)
              (db/clear-alerts))})))

(rf/reg-event-fx
 :artifact/download--success
 (fn [{:keys [db]} [_ art-id resp]]
   {:db (db/reset-downloading db art-id)
    ;; Artifacts are always tarballs, so add the extension.  Otherwise browsers won't know what to do with it.
    :download-link [(str art-id ".tgz") resp]}))

(rf/reg-event-db
 :artifact/download--failed
 (fn [db [_ art-id err]]
   (-> db
       (db/reset-downloading art-id)
       (db/set-alerts [{:type :danger
                        :message (str "Failed to to download artifact " art-id ": " (u/error-msg err))}]))))
