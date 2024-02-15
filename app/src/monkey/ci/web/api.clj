(ns monkey.ci.web.api
  (:require [camel-snake-kebab.core :as csk]
            [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [events :as e]
             [labels :as lbl]
             [logging :as l]
             [runtime :as rt]
             [storage :as st]
             [utils :as u]]
            [monkey.ci.web
             [auth :as auth]
             [common :as c]]
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

(def repo-id (partial id-from-name :repos))

(defn- repo->out [r]
  (dissoc r :customer-id))

(defn- repos->out
  "Converts the project repos into output format"
  [p]
  (some-> p
          (mc/update-existing :repos (comp (partial map repo->out) vals))))

(make-entity-endpoints "customer"
                       {:get-id (id-getter :customer-id)
                        :getter (comp repos->out st/find-customer)
                        :saver st/save-customer})

(make-entity-endpoints "repo"
                       ;; The repo is part of the customer, so combine the ids
                       {:get-id (id-getter (juxt :customer-id :repo-id))
                        :getter st/find-repo
                        :saver st/save-repo
                        :new-id repo-id})

(make-entity-endpoints "webhook"
                       {:get-id (id-getter :webhook-id)
                        :getter (comp #(dissoc % :secret-key)
                                      st/find-details-for-webhook)
                        :saver st/save-webhook-details})

(make-entity-endpoints "user"
                       {:get-id (id-getter (juxt :user-type :type-id))
                        :getter st/find-user
                        :saver st/save-user})

;; Override webhook creation
(defn- assign-webhook-secret
  "Updates the request body to assign a secret key, which is used to
   validate the request."
  [req]
  (assoc-in req [:parameters :body :secret-key] (auth/generate-secret-key)))

(def create-webhook (comp (entity-creator st/save-webhook-details default-id)
                          assign-webhook-secret))

(def repo-sid (comp (juxt :customer-id :repo-id)
                    :path
                    :parameters))
(def params-sid (comp (partial remove nil?)
                      repo-sid))
(def customer-id (comp :customer-id :path :parameters))

(defn- get-list-for-customer [finder req]
  (-> (c/req->storage req)
      (finder (customer-id req))
      (or [])
      (rur/response)))

(defn- update-for-customer [updater req]
  (let [p (body req)]
    ;; TODO Allow patching values so we don't have to send back all secrets to client
    (when (updater (c/req->storage req) (customer-id req) p)
      (rur/response p))))

(defn- get-for-repo-by-label
  "Uses the finder to retrieve a list of entities for the repository specified
   by the request.  Then filters them using the repo labels and their configured
   label filters.  Applies the transducer `tx` before constructing the response."
  [finder tx req]
  (let [st (c/req->storage req)
        sid (repo-sid req)
        repo (st/find-repo st sid)]
    (if repo
      (->> (finder st (customer-id req))
           (lbl/filter-by-label repo)
           (into [] tx)
           (rur/response))
      (rur/not-found {:message (format "Repository %s does not exist" sid)}))))

(def get-customer-params
  "Retrieves all parameters configured on the customer.  This is for administration purposes."
  (partial get-list-for-customer st/find-params))

(def get-repo-params
  "Retrieves the parameters that are available for the given repository.  This depends
   on the parameter label filters and the repository labels."
  (partial get-for-repo-by-label st/find-params (mapcat :parameters)))

(def update-params
  (partial update-for-customer st/save-params))

(def get-customer-ssh-keys
  (partial get-list-for-customer st/find-ssh-keys))

(def get-repo-ssh-keys
  (partial get-for-repo-by-label st/find-ssh-keys (map :private-key)))

(def update-ssh-keys
  (partial update-for-customer st/save-ssh-keys))

(defn- fetch-build-details [s sid]
  (log/debug "Fetching details for build" sid)
  (when (st/build-exists? s sid)
    (let [r (st/find-build-results s sid)
          md (st/find-build-metadata s sid)]
      (merge {:id (last sid)} md r))))

(defn- add-index [[idx p]]
  (assoc p :index idx))

(defn- pipelines->out [p]
  (letfn [(with-index [v]
            (->> v
                 (map add-index)
                 (sort-by :index)))]
    (->> (with-index p)
         (map #(mc/update-existing % :steps with-index)))))

(defn- get-builds*
  "Helper function that retrieves the builds using the request, then
   applies `f` to the resultset and fetches the details of the remaining builds."
  [req f]
  (let [s (c/req->storage req)
        sid (repo-sid req)
        builds (st/list-builds s sid)
        fetch-details (fn [id]
                        (-> (fetch-build-details s (st/->sid (concat sid [id])))
                            (update :pipelines pipelines->out)))]
    (->> builds
         (f)
         ;; TODO This is slow when there are many builds
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
    (if-let [b (some-> (fetch-build-details (c/req->storage req) sid)
                       (update :pipelines pipelines->out))]
      (rur/response b)
      (rur/not-found nil))))

(defn- params->ref
  "Creates a git ref from the query parameters (either branch or tag)"
  [p]
  (let [{{:keys [branch tag]} :query} p]
    (cond
      (some? branch)
      (str "refs/heads/" branch)
      (some? tag)
      (str "refs/tags/" tag))))

(defn make-build-ctx [{p :parameters :as req} bid]
  (let [acc (:path p)
        st (c/req->storage req)
        repo (st/find-repo st (repo-sid req))
        ssh-keys (->> (st/find-ssh-keys st (customer-id req))
                      (lbl/filter-by-label repo))]
    {:build-id bid
     :git (-> (:query p)
              (select-keys [:commit-id :branch])
              (assoc :url (:url repo)
                     :ssh-keys-dir (rt/ssh-keys-dir (c/req->rt req) bid))
              (mc/assoc-some :ref (params->ref p))
              (mc/assoc-some :ssh-keys ssh-keys))
     :sid (-> acc
              (assoc :build-id bid)
              (st/ext-build-sid))}))

(defn trigger-build [req]
  (let [{p :parameters} req]
    ;; TODO If no branch is specified, use the default
    (let [acc (:path p)
          bid (u/new-build-id)
          st (c/req->storage req)
          md (-> acc
                 (select-keys [:customer-id :repo-id])
                 (assoc :build-id bid
                        :source :api
                        :timestamp (System/currentTimeMillis)
                        :ref (params->ref p))
                 (merge (:query p)))
          runner (c/from-rt req :runner)]
      (log/debug "Triggering build for repo sid:" (repo-sid req))
      (if (st/create-build-metadata st md)
        (do
          ;; Trigger the build but don't wait for the result
          (runner (assoc (c/req->rt req) :build (make-build-ctx req bid)))
          (-> (rur/response {:build-id bid})
              (rur/status 202)))
        (-> (rur/response {:message "Unable to create build metadata"})
            (rur/status 500))))))

(defn list-build-logs [req]
  (let [build-sid (st/ext-build-sid (get-in req [:parameters :path]))
        retriever (c/from-rt req rt/log-retriever)]
    (rur/response (l/list-logs retriever build-sid))))

(defn download-build-log [req]
  (let [build-sid (st/ext-build-sid (get-in req [:parameters :path]))
        path (get-in req [:parameters :query :path])
        retriever (c/from-rt req rt/log-retriever)]
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
