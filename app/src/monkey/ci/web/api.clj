(ns monkey.ci.web.api
  (:require [camel-snake-kebab.core :as csk]
            [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [medley.core :as mc]
            [monkey.ci
             [context :as ctx]
             [events :as e]
             [logging :as l]
             [storage :as st]
             [utils :as u]]
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
    (let [id (get-id req)]
      (if-let [match (some-> (c/req->storage req)
                             (getter id))]
        (rur/response match)
        (do
          (log/warn "Entity not found:" id)
          (rur/not-found nil))))))

(defn- entity-creator [saver id-generator]
  (fn [req]
    (let [body (body req)
          st (c/req->storage req)
          c (assoc body :id (id-generator st body))]
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

(defn- default-id [_ _]
  (st/new-id))

(defn- make-entity-endpoints
  "Creates default api functions for the given entity using the configuration"
  [entity {:keys [get-id getter saver new-id] :or {new-id default-id}}]
  (letfn [(make-ep [[p f]]
            (intern *ns* (symbol (str p entity)) f))]
    (->> {"get-" (entity-getter get-id getter)
          "create-" (entity-creator saver new-id)
          "update-" (entity-updater get-id getter saver)}
         (map make-ep)
         (doall))))

(defn- id-from-name
  "Generates id from the object name.  It looks up the customer by `:customer-id`
   and finds existing objects using `existing-from-cust` to avoid collisions."
  [existing-from-cust st obj]
  (let [existing? (-> (:customer-id obj)
                      (as-> cid (st/find-customer st cid))
                      (existing-from-cust obj)
                      (keys)
                      (set))
        ;; TODO Check what happens with special chars
        new-id (csk/->kebab-case (:name obj))]
    (loop [id new-id
           idx 2]
      ;; Try a new id until we find one that does not exist yet.
      ;; Alternatively we could parse the ids to extract the max index (but yagni)
      (if (existing? id)
        (recur (str new-id "-" idx)
               (inc idx))
        id))))

(def repo-id (partial id-from-name #(get-in %1 [:projects (:project-id %2) :repos])))

(defn- repo->out [r]
  (dissoc r :customer-id :project-id))

(defn- repos->out
  "Converts the project repos into output format"
  [p]
  (some-> p
          (mc/update-existing :repos (comp (partial map repo->out) vals))))

(def project-id (partial id-from-name :projects))

(defn- project->out [p]
  (dissoc p :customer-id))

(defn- projects->out
  "Converts the customer projects into output format"
  [c]
  (some-> c
          (mc/update-existing :projects (comp (partial map (comp project->out repos->out)) vals))))

(make-entity-endpoints "customer"
                       {:get-id (id-getter :customer-id)
                        :getter (comp projects->out st/find-customer)
                        :saver st/save-customer})

(make-entity-endpoints "project"
                       ;; The project is part of the customer, so combine the ids
                       {:get-id (comp (juxt :customer-id :project-id) :path :parameters)
                        :getter st/find-project
                        :saver st/save-project
                        :new-id project-id})

(make-entity-endpoints "repo"
                       ;; The repo is part of the customer/project, so combine the ids
                       {:get-id (comp (juxt :customer-id :project-id :repo-id) :path :parameters)
                        :getter st/find-repo
                        :saver st/save-repo
                        :new-id repo-id})

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

(def create-webhook (comp (entity-creator st/save-webhook-details default-id)
                          assign-webhook-secret))

(def repo-sid (comp (juxt :customer-id :project-id :repo-id)
                    :path
                    :parameters))
(def params-sid (comp (partial remove nil?)
                      repo-sid))

(defn fetch-all-params
  "Fetches all params for the given sid from storage, adding all those from
   higher levels too."
  [st params-sid]
  (->> (loop [sid params-sid
              acc []]
         (if (empty? sid)
           acc
           (recur (drop-last sid)
                  (concat acc (st/find-params st (st/->sid sid))))))
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

(defn- fetch-build-details [s sid]
  (log/debug "Fetching details for build" sid)
  (when (st/build-exists? s sid)
    (let [r (st/find-build-results s sid)
          md (st/find-build-metadata s sid)]
      (merge {:id (last sid)} md r))))

(defn- get-builds*
  "Helper function that retrieves the builds using the request, then
   applies `f` to the resultset and fetches the details of the remaining builds."
  [req f]
  (let [s (c/req->storage req)
        sid (repo-sid req)
        builds (st/list-builds s sid)
        fetch-details (fn [id]
                        (fetch-build-details s (st/->sid (concat sid [id]))))]
    (->> builds
         (f)
         ;; TODO This could potentially be slow for many builds
         (map fetch-details))))

(defn get-builds
  "Lists all builds for the repository"
  [req]
  (-> req
      (get-builds* identity)
      (rur/response)))

(defn get-latest-build
  "Retrieves the latest build for the repository."
  [req]
  ;; FIXME This assumes the last item in the list is the most recent one, but
  ;; this may not always be the case.
  (if-let [r (-> req
                 (get-builds* (partial take-last 1))
                 first)]
    (rur/response r)
    (rur/status 204)))

(defn get-build
  "Retrieves build by id"
  [req]
  (let [sid (st/ext-build-sid (get-in req [:parameters :path]))]
    (if-let [b (fetch-build-details (c/req->storage req) sid)]
      (rur/response b)
      (rur/not-found nil))))

(defn trigger-build [req]
  (c/posting-handler
   req
   (fn [{p :parameters}]
     (let [acc (:path p)
           bid (u/new-build-id)
           repo-sid ((juxt :customer-id :project-id :repo-id) acc)
           st (c/req->storage req)
           repo (st/find-repo st repo-sid)
           md (-> acc
                  (select-keys [:customer-id :project-id :repo-id])
                  (assoc :build-id bid
                         :source :api
                         :timestamp (str (jt/instant)))
                  (merge (:query p)))]
       (log/debug "Triggering build for repo sid:" repo-sid)
       (when (st/create-build-metadata st md)
         {:type :build/triggered
          :account acc
          :build {:build-id bid
                  :git (-> (:query p)
                           (select-keys [:commit-id :branch])
                           (assoc :url (:url repo)))
                  :sid (-> acc
                           (assoc :build-id bid)
                           (st/ext-build-sid))}})))))

(defn list-build-logs [req]
  (let [build-sid (st/ext-build-sid (get-in req [:parameters :path]))
        retriever (c/from-context req ctx/log-retriever)]
    (rur/response (l/list-logs retriever build-sid))))

(defn download-build-log [req]
  (let [build-sid (st/ext-build-sid (get-in req [:parameters :path]))
        path (get-in req [:parameters :query :path])
        retriever (c/from-context req ctx/log-retriever)]
    (if-let [r (l/fetch-log retriever build-sid path)]
      (-> (rur/response r)
          (rur/content-type "text/plain"))
      (rur/not-found nil))))

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
