(ns monkey.ci.web.api
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [monkey.ci
             [events :as e]
             [storage :as st]]
            [monkey.ci.web
             [common :as c]
             [github :as gh]]
            [org.httpkit.server :as http]
            [ring.util.response :as rur]))

(def body (comp :body :parameters))

(defn- id-getter [id-key]
  (comp id-key :path :parameters))

(defn- entity-getter [get-id getter]
  (fn [req]
    (if-let [match (some-> (c/req->storage req)
                           (getter (get-id req)))]
      (rur/response match)
      (rur/not-found nil))))

(defn- entity-creator [saver]
  (fn [req]
    (let [c (-> (body req)
                (assoc :id (st/new-id)))
          st (c/req->storage req)]
      (when (saver st c)
        ;; TODO Return full url to the created entity
        (rur/created (:id c) c)))))

(defn- entity-updater [get-id getter saver]
  (fn [req]
    (let [st (c/req->storage req)]
      (if-let [match (getter st (get-id req))]
        (let [upd (merge match (body req))]
          (when (saver st upd)
            (rur/response upd)))
        ;; If no entity to update is found, return a 404.  Alternatively,
        ;; we could create it here instead and return a 201.  This could
        ;; be useful should we ever want to restore lost data.
        (rur/not-found nil)))))

(defn- make-entity-endpoints
  "Creates default api functions for the given entity using the configuration"
  [entity {:keys [get-id getter saver]}]
  (letfn [(make-ep [[p f]]
            (intern *ns* (symbol (str p entity)) f))]
    (->> {"get-" (entity-getter get-id getter)
          "create-" (entity-creator saver)
          "update-" (entity-updater get-id getter saver)}
         (map make-ep)
         (doall))))

(make-entity-endpoints "customer"
                       {:get-id (id-getter :customer-id)
                        :getter st/find-customer
                        :saver st/save-customer})

(make-entity-endpoints "project"
                       ;; The project is part of the customer, so combine the ids
                       {:get-id (comp (juxt :customer-id :project-id) :path :parameters)
                        :getter st/find-project
                        :saver st/save-project})

(make-entity-endpoints "repo"
                       ;; The repo is part of the customer/project, so combine the ids
                       {:get-id (comp (juxt :customer-id :project-id :repo-id) :path :parameters)
                        :getter st/find-repo
                        :saver st/save-repo})

(make-entity-endpoints "webhook"
                       {:get-id (id-getter :webhook-id)
                        :getter (comp #(dissoc % :secret-key)
                                      st/find-details-for-webhook)
                        :saver st/save-webhook-details})

;; Override webhook creation
(defn- assign-webhook-secret
  "Updates the request body to assign a secret key, which is used to
   validate the request."
  [req]
  (assoc-in req [:parameters :body :secret-key] (gh/generate-secret-key)))

(def create-webhook (comp (entity-creator st/save-webhook-details)
                          assign-webhook-secret))

(def params-sid (comp (partial remove nil?)
                      (juxt :customer-id :project-id :repo-id)
                      :path
                      :parameters))

(defn fetch-all-params
  "Fetches all params for the given sid from storage, adding all those from
   higher levels too."
  [st params-sid]
  (->> (loop [sid params-sid
              acc []]
         (if (empty? sid)
           acc
           (recur (drop-last sid)
                  (concat acc (st/find-params st sid)))))
       (group-by :name)
       (vals)
       (map first)))

(defn get-params
  "Retrieves build parameters for the given location.  This could be at customer, 
   project or repo level.  For lower levels, the parameters for the higher levels
   are merged in."
  [req]
  ;; TODO Allow to retrieve only for the specified level using query param
  ;; TODO Return 404 if customer, project or repo not found.
  (-> (c/req->storage req)
      (fetch-all-params (params-sid req))
      (rur/response)))

(defn update-params [req]
  (let [p (body req)]
    (when (st/save-params (c/req->storage req) (params-sid req) p)
      (rur/response p))))

(def allowed-events
  #{:script/start
    :script/end
    :pipeline/start
    :pipeline/end
    :step/start
    :step/end})

(defn event-stream
  "Sets up an event stream for the specified filter."
  [req]
  (let [{:keys [mult]} (c/req->bus req)
        dest (ca/chan (ca/sliding-buffer 10)
                      (filter (comp allowed-events :type)))
        make-reply (fn [evt]
                     (-> evt
                         (prn-str)
                         (rur/response)
                         (rur/header "Content-Type" "text/event-stream")))
        sender (fn [ch]
                 (fn [msg]
                   (when-not (http/send! ch msg false)
                     (log/warn "Failed to send message to channel"))))
        send-events (fn [src ch]
                      (ca/go-loop [msg (ca/<! src)]
                        (if msg
                          (if (http/send! ch (make-reply msg) false)
                            (recur (ca/<! src))
                            (do
                              (log/warn "Could not send message to channel, stopping event transmission")
                              (ca/untap mult src)
                              (ca/close! src)))
                          (do
                            (log/debug "Event bus was closed, stopping event transmission")
                            (http/send! ch (rur/response "") true)))))]
    (http/as-channel
     req
     {:on-open (fn [ch]
                 (log/debug "Event stream opened:" ch)
                 (ca/tap mult dest)
                 ;; Pipe the messages from the tap to the channel
                 (send-events dest ch))
      :on-close (fn [_ status]
                  (ca/untap mult dest)
                  (log/debug "Event stream closed with status" status))})))
