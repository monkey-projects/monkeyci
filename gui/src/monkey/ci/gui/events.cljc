(ns monkey.ci.gui.events
  (:require [re-frame.core :as rf]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.utils :as u]))

(rf/reg-event-db
 :initialize-db
 (fn [_ _]
   {}))

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

