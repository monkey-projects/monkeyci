(ns monkey.ci.gui.login.events
  (:require [monkey.ci.gui.login.db :as db]
            [monkey.ci.gui.routing :as r]
            [re-frame.core :as rf]))

(rf/reg-event-db
 :login/submit
 (fn [db [_ fd]]
   (db/set-submitting db)))

(rf/reg-event-db
 :login/authenticated
 (fn [db [_ user]]
   (-> db
       (db/set-user user)
       (db/unset-submitting))))

(rf/reg-event-fx
 :login/github-code-received
 (fn [_ [_ code]]
   {:dispatch [:martian.re-frame/request
               :github-exchange-code
               {:code code}
               [:login/github-code-received--success]
               [:login/github-code-received--failed]]}))

(rf/reg-event-fx
 :login/github-code-received--success
 (fn [{:keys [db]} [_ {u :body}]]
   (println "Got user details:" u)
   {:db (db/set-user db u)
    ;; TODO Store user in browser local storage, because the redirect loses the memory db
    :dispatch [:route/goto (r/path-for :page/root)]}))

(rf/reg-event-db
 :login/github-code-received--failed
 (fn [db [_ err]]
   (println "Failed to exchange github code:" err)
   db))
