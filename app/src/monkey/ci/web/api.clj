(ns monkey.ci.web.api
  (:require [camel-snake-kebab.core :as csk]
            [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [stream :as ms]]
            [medley.core :as mc]
            [monkey.ci
             [labels :as lbl]
             [logging :as l]
             [runtime :as rt]
             [storage :as st]
             [utils :as u]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.web
             [auth :as auth]
             [common :as c]]
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

(defn fetch-build-details [s sid]
  (log/debug "Fetching details for build" sid)
  ;; TODO Remove this legacy stuff after a while
  (if (st/legacy-build-exists? s sid)
    (-> (st/find-build-metadata s sid)
        (merge (st/find-build-results s sid))
        (assoc :legacy? true))
    (if (st/build-exists? s sid)
      (st/find-build s sid))))

(defn- add-index [[idx p]]
  (assoc p :index idx))

(defn- pipelines->out
  "Converts legacy pipelines to job output format"
  [p]
  (letfn [(with-index [v]
            (->> v
                 (map add-index)
                 (sort-by :index)))
          (rename-steps [p]
            (mc/assoc-some p :jobs (:steps p)))
          (assign-id [pn {:keys [name index] :as job}]
            (-> job
                (assoc :id (or name (str pn "-" index)))
                (dissoc :name :index)))
          (add-pipeline-lbl [n j]
            (assoc-in j [:labels "pipeline"] n))
          (convert-jobs [{:keys [jobs] n :name}]
            (->> (with-index jobs)
                 (map (partial assign-id n))
                 (map (partial add-pipeline-lbl n))))]
    (->> (with-index p)
         (map rename-steps)
         (mapcat convert-jobs))))

(defn build->out
  "Converts build to output format.  This means converting legacy builds with pipelines,
   jobs and regular builds with jobs."
  [b]
  (letfn [(convert-legacy [{:keys [jobs pipelines] :as b}]
            (cond-> (dissoc b :jobs :pipelines :legacy? :timestamp :result)
              true (mc/assoc-some :start-time (:timestamp b)
                                  :status (:result b)
                                  :git {:ref (:ref b)})
              jobs (assoc-in [:script :jobs] (vals jobs))
              pipelines (assoc-in [:script :jobs] (pipelines->out pipelines))))
          (maybe-add-job-id [[id job]]
            (cond-> job
              (nil? (:id job)) (assoc :id (name id))))
          (convert-regular [b]
            (mc/update-existing-in b [:script :jobs] (partial map maybe-add-job-id)))]
    (if (:legacy? b)
      (convert-legacy b)
      (convert-regular b))))

(defn- fetch-and-convert [s sid id]
  (-> (fetch-build-details s (st/->sid (concat sid [id])))
      (build->out)))

(defn- get-builds*
  "Helper function that retrieves the builds using the request, then
   applies `f` to the resultset and fetches the details of the remaining builds."
  [req f]
  (let [s (c/req->storage req)
        sid (repo-sid req)
        builds (st/list-builds s sid)]
    (->> builds
         (f)
         ;; TODO This is slow when there are many builds
         (map (partial fetch-and-convert s sid)))))

(defn get-builds
  "Lists all builds for the repository"
  [req]
  (-> req
      (get-builds* identity)
      (rur/response)))

(defn get-latest-build
  "Retrieves the latest build for the repository."
  [req]
  (if-let [r (-> req
                 ;; This assumes the build name is time-based
                 (get-builds* (comp (partial take-last 1) sort))
                 first)]
    (rur/response r)
    (rur/status 204)))

(defn get-build
  "Retrieves build by id"
  [req]
  (if-let [b (fetch-and-convert
              (c/req->storage req)
              (repo-sid req)
              (get-in req [:parameters :path :build-id]))]
    (rur/response b)
    (rur/not-found nil)))

(defn- params->ref
  "Creates a git ref from the query parameters (either branch or tag)"
  [p]
  (let [{{:keys [branch tag]} :query} p]
    (cond
      (some? branch)
      (str "refs/heads/" branch)
      (some? tag)
      (str "refs/tags/" tag))))

(defn make-build-ctx
  "Creates a build object from the request"
  [{p :parameters :as req} bid]
  (let [acc (:path p)
        st (c/req->storage req)
        repo (st/find-repo st (repo-sid req))
        ssh-keys (->> (st/find-ssh-keys st (customer-id req))
                      (lbl/filter-by-label repo))]
    (-> acc
        (select-keys [:customer-id :repo-id])
        (assoc :source :api
               :build-id bid
               :git (-> (:query p)
                        (select-keys [:commit-id :branch])
                        (assoc :url (:url repo)
                               :ssh-keys-dir (rt/ssh-keys-dir (c/req->rt req) bid))
                        (mc/assoc-some :ref (params->ref p))
                        (mc/assoc-some :ssh-keys ssh-keys))
               :sid (-> acc
                        (assoc :build-id bid)
                        (st/ext-build-sid))
               :start-time (u/now)
               :status :running
               :cleanup? true))))

(defn trigger-build [req]
  (let [{p :parameters} req]
    ;; TODO If no branch is specified, use the default
    (let [acc (:path p)
          bid (u/new-build-id)
          st (c/req->storage req)
          runner (c/from-rt req :runner)
          build (make-build-ctx req bid)]
      (log/debug "Triggering build for repo sid:" (repo-sid req))
      (if (st/save-build st build)
        (do
          ;; Trigger the build but don't wait for the result
          (c/run-build-async (assoc (c/req->rt req) :build build))
          (-> (rur/response {:build-id bid})
              (rur/status 202)))
        (-> (rur/response {:message "Unable to create build"})
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
  #{:build/start
    :build/end
    :script/start
    :script/end
    :job/start
    :job/end})

(defn event-stream
  "Sets up an event stream for the specified filter."
  [req]
  (let [cid (customer-id req)
        recv (c/from-rt req rt/events-receiver)
        stream (ms/stream)
        make-reply (fn [evt]
                     ;; Format according to sse specs, with double newline at the end
                     (str "data: " (pr-str evt) "\n\n"))
        for-cust? (fn [{:keys [sid]}]
                    ;; Allow either events without sid or where the customer is the first component
                    (or (nil? sid) (= cid (first sid))))
        listener (ec/no-dispatch
                  (fn [evt]
                    ;; Only send events for the customer specified in the url
                    (when (and (allowed-events (:type evt)) (for-cust? evt))
                      (ms/put! stream (make-reply evt)))))]
    (ms/on-drained stream
                   (fn []
                     (log/info "Closing event stream")
                     (ec/remove-listener recv listener)))
    (ec/add-listener recv listener)
    (-> (rur/response stream)
        (rur/header "content-type" "text/event-stream")
        (rur/header "access-control-allow-origin" "*"))))
