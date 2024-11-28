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
  (:require [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

;;; DB functions

(defn loading?
  [db id]
  (true? (get-in db [id :loading?])))

(defn set-loading
  [db id]
  (assoc-in db [id :loading?] true))

(defn reset-loading
  [db id]
  (update db id dissoc :loading?))

(defn loaded?
  [db id]
  (true? (get-in db [id :loaded?])))

(defn set-loaded
  [db id]
  (assoc-in db [id :loaded?] true))

(defn reset-loaded
  [db id]
  (update db id dissoc :loaded?))

(defn initialized?
  [db id]
  (true? (get-in db [id :initialized?])))

(defn set-initialized
  [db id]
  (assoc-in db [id :initialized?] true))

(defn reset-initialized
  [db id]
  (update db id dissoc :initialized?))

(defn set-value [db id d]
  (assoc-in db [id :value] d))

(defn update-value [db id f & args]
  (apply update-in db [id :value] f args))

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

(defn clear-all
  "Removes all loader info associated with given id"
  [db id]
  (dissoc db id))

;;; Event handler utilities

(defn before-request
  "Sets properties in db before the request"
  [db id]
  (-> db
      (set-loading id)
      (reset-alerts id)))

(defn loader-evt-handler
  "Creates a fn that is an fx event handler that dispatches a request event
   and prepares db using `before-request`.  The request builder is passed the id
   event context, and event vector as received by the handler."
  [id request-builder]
  (fn [{:keys [db] :as ctx} evt]
    {:dispatch (request-builder id ctx evt)
     :db (-> db
             (reset-alerts id)
             (before-request id))}))

(defn on-success
  "Handles the success response for given id"
  [db id resp]
  (-> db
      (set-value id (:body resp))
      (reset-loading id)
      (set-loaded id)))

(defn on-failure
  "Handles the failure response for given id"
  [db id msg err]
  (-> db
      (reset-loading id)
      (set-alerts id [(if (fn? msg)
                        (msg err)
                        ;; Provided for backwards compatibility
                        {:type :danger
                         :message (str msg (u/error-msg err))})])))

(defn on-initialize
  "Creates an fx context when initializing a screen that prepares the db, and
   dispatches any initialization events.  If an event handler is provided, it
   will also start an event stream using the id."
  [db id {:keys [init-events leave-event event-handler-event]}]
  (when-not (initialized? db id)
    (log/debug "Initializing:" (str id))
    (cond-> {:db (set-initialized db id)}
      init-events
      (assoc :dispatch-n init-events)

      leave-event
      (update :dispatch-n (fnil conj []) [:route/on-page-leave leave-event])

      event-handler-event
      (update :dispatch-n (fnil conj []) [:event-stream/start id (r/customer-id db) event-handler-event]))))

(defn on-leave
  "Creates a context for an leave fx handler that clears the initialized flag
   and dispatches a stream stop event."
  [db id]
  ;; It would be cleaner if we only dispatched this event if there was actually a started stream
  {:dispatch [:event-stream/stop id]
   :db (reset-initialized db id)})

;;; Common subs

(u/db-sub :loader/alerts get-alerts)
(u/db-sub :loader/loading? loading?)
(u/db-sub :loader/loaded? loaded?)
(u/db-sub :loader/value get-value)

(rf/reg-sub
 :loader/init-loading?
 (fn [db [_ id]]
   ;; True when loading for the first time
   (and (not (loaded? db id)) (loading? db id))))
