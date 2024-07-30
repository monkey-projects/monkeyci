(ns monkey.ci.gui.loader
  "Event handlers and subs for an often occurring pattern: the loading of
   information (usually from an ajax request).  This boils down to usually
   the same steps:
     1. Set a flag in db that we're loading
     2. Send request
     3. On success, set data in db, and reset loading flag
     4. On failure, set an error alert and reset loading flag too
   We usually also want a flag to see if we've loaded this info already and
   in that case, don't load it again (optionally).
   
   This namespace provides functionality to support this pattern."
  (:require [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(defn loading?
  [db id]
  (true? (get-in db [id :loading?])))

(defn set-loading
  [db id]
  (assoc-in db [id :loading?] true))

(defn reset-loading
  [db id]
  (update db id dissoc :loading?))

(defn set-value [db id d]
  (assoc-in db [id :value] d))

(defn get-value [db id]
  (get-in db [id :value]))

(defn set-alerts
  [db id a]
  (assoc-in db [id :alerts] a))

(defn get-alerts [db id]
  (get-in db [id :alerts]))

(defn reset-alerts
  [db id]
  (update db id dissoc :alerts))

(defn before-request
  "Sets properties in db before the request"
  [db id]
  (-> db
      (set-loading id)
      (reset-alerts id)))

(defn loader-fn
  "Creates a fn that is an fx event handler that dispatches a request event
   and prepares db using `before-request`.  The request builder is passed the id
   event context, and event vector as received by the handler."
  [id request-builder]
 (fn [{:keys [db] :as ctx} evt]
   {:dispatch (request-builder id ctx evt)
    :db (before-request db id)}))

(defn on-success
  "Handles the success response for given id"
  [db id resp]
  (-> db
      (set-value id (:body resp))
      (reset-loading id)))

(defn on-failure
  "Handles the failure response for given id"
  [db id msg err]
  (-> db
      (reset-loading id)
      (set-alerts id [{:type :danger
                       :message (str msg (u/error-msg err))}])))
