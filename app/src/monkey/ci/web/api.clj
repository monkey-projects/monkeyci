(ns monkey.ci.web.api
  (:require [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [artifacts :as a]
             [blob :as blob]
             [build :as b]
             [labels :as lbl]
             [logging :as l]
             [runtime :as rt]
             [storage :as st]
             [time :as t]]
            [monkey.ci.events.http :as eh]
            [monkey.ci.web
             [auth :as auth]
             [common :as c]
             [response :as r]]
            [ring.util.response :as rur]))

(def body c/body)

(def repo-id c/gen-repo-display-id)

(c/make-entity-endpoints "repo"
                         ;; The repo is part of the customer, so combine the ids
                         {:get-id (c/id-getter (juxt :customer-id :repo-id))
                          :getter st/find-repo
                          :saver st/save-repo
                          :deleter st/delete-repo
                          :new-id repo-id})

(c/make-entity-endpoints "webhook"
                         {:get-id (c/id-getter :webhook-id)
                          :getter (comp #(dissoc % :secret-key)
                                        st/find-webhook)
                          :saver st/save-webhook})

(c/make-entity-endpoints "user"
                         {:get-id (c/id-getter (juxt :user-type :type-id))
                          :getter st/find-user-by-type
                          :saver st/save-user})

(defn get-user-customers
  "Retrieves all users linked to the customer in the request path"
  [req]
  (let [user-id (get-in req [:parameters :path :user-id])
        st (c/req->storage req)]
    (rur/response (st/list-user-customers st user-id))))

;; Override webhook creation
(defn- assign-webhook-secret
  "Updates the request body to assign a secret key, which is used to
   validate the request."
  [req]
  (assoc-in req [:parameters :body :secret-key] (auth/generate-secret-key)))

(def create-webhook (comp (c/entity-creator st/save-webhook c/default-id)
                          assign-webhook-secret))

(def customer-id c/customer-id)

(def get-customer-ssh-keys
  (partial c/get-list-for-customer (comp c/drop-ids st/find-ssh-keys)))

(def get-repo-ssh-keys
  (partial c/get-for-repo-by-label (comp c/drop-ids st/find-ssh-keys) (map :private-key)))

(def update-ssh-keys
  (partial c/update-for-customer st/save-ssh-keys))

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

(defn get-builds
  "Lists all builds for the repository"
  [req]
  (->> (st/list-builds (c/req->storage req) (c/repo-sid req))
       (map build->out)
       (rur/response)))

(defn get-latest-build
  "Retrieves the latest build for the repository."
  [req]
  (if-let [r (some-> (st/find-latest-build (c/req->storage req) (c/repo-sid req))
                     (build->out))]
    (rur/response r)
    (rur/status 204)))

(defn get-build
  "Retrieves build by id"
  [req]
  (if-let [b (some-> (st/find-build
                      (c/req->storage req)
                      (st/->sid (concat (c/repo-sid req)
                                        [(get-in req [:parameters :path :build-id])])))
                     (build->out))]
    (rur/response b)
    (rur/not-found nil)))

(defn- as-ref [k v]
  (fn [p]
    (when-let [r (get-in p [:query k])]
      (format "refs/%s/%s" v r))))

(def build-sid c/build-sid)

(defn- with-artifacts [req f]
  (if-let [b (st/find-build (c/req->storage req)
                            (build-sid req))]
    (let [res (->> (b/all-jobs b)
                   (mapcat :save-artifacts)
                   (f))
          ->response (fn [res]
                       (if (or (nil? res) (and (sequential? res) (empty? res)))
                         (rur/status 204)
                         (rur/response res)))]
      (->response (if (md/deferred? res)
                    @res ; Deref otherwise cors middleware fails
                    res)))
    (rur/not-found nil)))

(defn get-build-artifacts
  "Lists all artifacts produced by the given build"
  [req]
  (with-artifacts req identity))

(defn- artifact-by-id [id art]
  (->> art
       (filter (comp (partial = id) :id))
       (first)))

(def artifact-id (comp :artifact-id :path :parameters))

(defn get-artifact
  "Returns details about a single artifact"
  [req]
  (with-artifacts req (partial artifact-by-id (artifact-id req))))

(defn download-artifact
  "Downloads the artifact contents.  Returns a stream that contains the raw zipped
   files directly from the blob store.  It is up to the caller to unzip the archive."
  [req]
  (letfn [(get-contents [{:keys [id]}]
            (when id
              (let [store (rt/artifacts (c/req->rt req))
                    path (a/build-sid->artifact-path (build-sid req) id)]
                (log/debug "Downloading artifact for id" id "from path" path)
                (blob/input-stream store path))))]
    (with-artifacts req (comp get-contents
                              (partial artifact-by-id (artifact-id req))))))

(def params->ref
  "Creates a git ref from the query parameters (either branch or tag)"
  (some-fn (as-ref :branch "heads")
           (as-ref :tag "tags")))

(defn- initialize-build [build]
  (assoc build
         :id (st/new-id)
         :source :api
         :start-time (t/now)
         :status :pending))

(defn make-build-ctx
  "Creates a build object from the request for the repo"
  [{p :parameters :as req} repo]
  (let [{st :storage :as rt} (c/req->rt req)
        ssh-keys (c/find-ssh-keys st repo)]
    (-> (:path p)
        (select-keys [:customer-id :repo-id])
        (initialize-build)
        (assoc :git (-> (:query p)
                        (select-keys [:commit-id :branch :tag])
                        (assoc :url (:url repo))
                        (mc/assoc-some :ref (or (params->ref p)
                                                (some->> (:main-branch repo) (str "refs/heads/")))
                                       :ssh-keys ssh-keys
                                       :main-branch (:main-branch repo)))))))

(defn- build-triggered-response [build]
  (-> (rur/response (select-keys build [:id]))
      (r/add-event (b/build-triggered-evt build))
      (rur/status 202)))

(defn trigger-build [req]
  (let [{p :parameters} req
        st (c/req->storage req)
        repo-sid (c/repo-sid req)
        repo (st/find-repo st repo-sid)
        build (make-build-ctx req repo)]
    (log/debug "Triggering build for repo sid:" repo-sid)
    (if repo
      (build-triggered-response build)
      (rur/not-found {:message "Repository does not exist"}))))

(defn retry-build
  "Re-triggers existing build by id"
  [req]
  (let [st (c/req->storage req)
        sid (c/build-sid req)
        existing (st/find-build st sid)
        rt (c/req->rt req)
        repo (st/find-repo st (take 2 sid))
        ssh-keys (c/find-ssh-keys st repo)
        build (some-> existing
                      (dissoc :start-time :end-time :script :build-id :idx)
                      (initialize-build)
                      (cond-> (not-empty ssh-keys)
                        (assoc-in [:git :ssh-keys] ssh-keys)))]
    (if build
      (build-triggered-response build)
      (rur/not-found {:message "Build not found"}))))

(defn cancel-build
  "Cancels running build"
  [req]
  (if-let [build (st/find-build (c/req->storage req) (c/build-sid req))]
    (-> (rur/status 202)
        (r/add-event (b/build-evt :build/canceled build)))
    (rur/not-found {:message "Build not found"})))

(defn list-build-logs [req]
  (let [build-sid (c/build-sid req)
        retriever (c/from-rt req rt/log-retriever)]
    (rur/response (l/list-logs retriever build-sid))))

(defn download-build-log [req]
  (let [build-sid (c/build-sid req)
        path (get-in req [:parameters :query :path])
        retriever (c/from-rt req rt/log-retriever)]
    (if-let [r (l/fetch-log retriever build-sid path)]
      (-> (rur/response r)
          (rur/content-type "text/plain"))
      (rur/not-found nil))))

(defn event-stream
  "Sets up an event stream for all `build/updated` events for the customer specified in the
   request path."
  [req]
  (eh/bus-stream (c/from-rt req :update-bus)
                 #{:build/updated}
                 (comp (partial = (customer-id req)) first :sid)))

(c/make-entity-endpoints "email-registration"
                         {:get-id (c/id-getter :email-registration-id)
                          :getter st/find-email-registration
                          :deleter st/delete-email-registration})

(defn create-email-registration
  "Custom creation endpoint that ensures emails are not registered twice."
  [req]
  (let [st (c/req->storage req)
        {:keys [email] :as body} (-> (c/body req)
                                     (assoc :id (st/new-id)))]
    (if-let [existing (st/find-email-registration-by-email st email)]
      (rur/response existing)
      (when (st/save-email-registration st body)
        (rur/created (:id body) body)))))
