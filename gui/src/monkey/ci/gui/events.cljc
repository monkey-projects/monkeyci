(ns monkey.ci.gui.events
  (:require [re-frame.core :as rf]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.login.events :as le]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.utils :as u]))

(rf/reg-event-db
 :initialize-db
 (fn [_ _]
   {}))

(rf/reg-event-fx
 :core/init-user
 [(rf/inject-cofx :local-storage ldb/storage-token-id)]
 (fn [{:keys [db] :as fx} _]
   (let [{:keys [github-token bitbucket-token] :as user} (:local-storage fx)]
     (-> {:db (cond-> db
                (not-empty user) (-> (ldb/set-user (dissoc user :token :github-token :bitbucket-token))
                                     (ldb/set-token (:token user))
                                     (ldb/set-github-token github-token)
                                     (ldb/set-bitbucket-token bitbucket-token)))}
         (le/try-load-github-user github-token)
         (le/try-load-bitbucket-user bitbucket-token)))))

(rf/reg-event-fx
 :core/load-version
 (fn [_ _]
   {:dispatch [:martian.re-frame/request
               :get-version
               {}
               [:core/load-version--success]
               [:core/load-version--failed]]}))

(rf/reg-event-db
 :core/load-version--success
 (fn [db [_ {version :body}]]
   (assoc db :version version)))

(rf/reg-event-db
 :core/load-version--failed
 (fn [db [_ err]]
   (log/warn "Unable to retrieve app version:" (u/error-msg err))
   (dissoc db :version)))

