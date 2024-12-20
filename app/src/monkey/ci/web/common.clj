(ns monkey.ci.web.common
  (:require [buddy.auth :as ba]
            [camel-snake-kebab.core :as csk]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [muuntaja.core :as mc]
            [monkey.ci
             [build :as b]
             [labels :as lbl]
             [protocols :as p]
             [runtime :as rt]
             [storage :as st]
             [vault :as v]]
            [monkey.ci.events.core :as ec]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware
             [exception :as rrme]
             [muuntaja :as rrmm]
             [parameters :as rrmp]]
            [ring.util.response :as rur]
            [schema.core :as s]))

(def not-empty-str (s/constrained s/Str not-empty))
(def Id not-empty-str)
(def Name not-empty-str)

(def body
  "Retrieves request body"
  (comp :body :parameters))

(def customer-id (comp :customer-id :path :parameters))

(def repo-sid (comp (juxt :customer-id :repo-id)
                    :path
                    :parameters))

(def build-sid (comp (juxt :customer-id :repo-id :build-id)
                     :path
                     :parameters))

(defn error-response
  ([error-msg status]
   (-> (rur/response {:error error-msg})
       (rur/status status)))
  ([error-msg]
   (error-response error-msg 400)))

;; Reitit rewrites records in the data to hashmaps, so wrap it in a type
(deftype RuntimeWrapper [runtime])

(defn req->rt
  "Gets the runtime from the request"
  [req]
  (some-> (get-in req [:reitit.core/match :data ::runtime])
          (.runtime)))

(defn from-rt
  "Applies `f` to the request runtime"
  [req f]
  (f (req->rt req)))

(def rt->storage :storage)

(defn req->storage
  "Retrieves storage object from the request context"
  [req]
  (from-rt req rt->storage))

(defn req->ext-uri
  "Determines external host address using configuration, or request properties"
  [req base]
  (or (-> req (req->rt) :config :api :ext-url)
      (let [idx (cs/index-of (:uri req) base)]
        (format "%s://%s%s" (name (:scheme req)) (get-in req [:headers "host"]) (subs (:uri req) 0 idx)))))

(def req->vault #(from-rt % :vault))

(defn id-getter [id-key]
  (comp id-key :path :parameters))

(defn entity-getter
  "Creates a generic request handler to retrieve a single entity by id"
  [get-id getter]
  (fn [req]
    (let [id (get-id req)]
      (if-let [match (some-> (req->storage req)
                             (getter id))]
        (rur/response match)
        (do
          (log/warn "Entity not found:" id)
          (rur/not-found nil))))))

(defn entity-creator
  "Request handler to create a new entity"
  [saver id-generator]
  (fn [req]
    (let [body (body req)
          st (req->storage req)
          c (assoc body :id (id-generator st body))]
      (when (saver st c)
        ;; TODO Return full url to the created entity
        (rur/created (:id c) c)))))

(defn entity-updater
  "Request handler to update and existing entity"
  [get-id getter saver]
  (fn [req]
    (let [st (req->storage req)]
      (if-let [match (getter st (get-id req))]
        (let [upd (merge match (body req))]
          (when (saver st upd)
            (rur/response upd)))
        ;; If no entity to update is found, return a 404.  Alternatively,
        ;; we could create it here instead and return a 201.  This could
        ;; be useful should we ever want to restore lost data.
        (rur/not-found nil)))))

(defn entity-deleter
  "Request handler to delete an entity"
  [get-id deleter]
  (fn [req]
    (rur/status (if (deleter (req->storage req) (get-id req))
                  204
                  404))))

(defn default-id [_ _]
  (st/new-id))

(defn make-entity-endpoints
  "Creates default api functions for the given entity using the configuration"
  [entity {:keys [get-id getter saver deleter new-id] :or {new-id default-id}}]
  (letfn [(make-ep [[p f]]
            (intern *ns* (symbol (str p entity)) f))]
    (->> (cond-> {"get-" (entity-getter get-id getter)}
           saver (assoc "create-" (entity-creator saver new-id)
                        "update-" (entity-updater get-id getter saver))
           deleter (assoc "delete-" (entity-deleter get-id deleter)))
         (map make-ep)
         (doall))))

(def drop-ids (partial map #(dissoc % :customer-id)))

(defn get-list-for-customer
  "Utility function that uses the `finder` to fetch a list of things from storage
   using the customer id from the request.  Returns the result as a http response."
  [finder req]
  (-> (req->storage req)
      (finder (customer-id req))
      (or [])
      (rur/response)))

(defn update-for-customer
  "Uses the `updater` to save the request body using the customer id.  Returns the 
   body with an id as a http response."
  [updater req]
  (let [assign-id (fn [{:keys [id] :as obj}]
                    (cond-> obj
                      (nil? id) (assoc :id (st/new-id))))
        p (->> (body req)
               (map assign-id))]
    ;; TODO Allow patching values so we don't have to send back all secrets to client
    (when (updater (req->storage req) (customer-id req) p)
      (rur/response p))))

(defn get-for-repo-by-label
  "Uses the finder to retrieve a list of entities for the repository specified
   by the request.  Then filters them using the repo labels and their configured
   label filters.  Applies the transducer `tx` before constructing the response."
  [finder tx req]
  (let [st (req->storage req)
        sid (repo-sid req)
        repo (st/find-repo st sid)]
    (if repo
      (->> (finder st (customer-id req))
           (lbl/filter-by-label repo)
           (into [] tx)
           (rur/response))
      (rur/not-found {:message (format "Repository %s does not exist" sid)}))))

(defn gen-repo-display-id
  "Generates id from the object name.  It lists existing repository display ids
   and generates an id from the name.  If the display id is already taken, it adds
   an index."
  [st obj]
  (let [existing? (-> (:customer-id obj)
                      (as-> cid (st/list-repo-display-ids st cid))
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

(defn make-muuntaja
  "Creates muuntaja instance with custom settings"
  []
  (mc/create
   (-> mc/default-options
       (assoc-in 
        ;; Convert keys to kebab-case
        [:formats "application/json" :decoder-opts]
        {:decode-key-fn csk/->kebab-case-keyword})
       (assoc-in
        [:formats "application/json" :encoder-opts]
        {:encode-key-fn (comp csk/->camelCase name)}))))

(defn- exception-logger [h]
  (fn [req]
    (try
      (h req)
      (catch Exception ex
        ;; Log and rethrow
        (log/error (str "Got error while handling request: " (:uri req)) ex)
        (throw ex)))))

(def exception-middleware
  (rrme/create-exception-middleware
   (merge rrme/default-handlers
          {:auth/unauthorized (fn [e req]
                                (if (ba/authenticated? req)
                                  {:status 403
                                   :body (.getMessage e)}
                                  {:status 401
                                   :body "Unauthenticated"}))})))

(def default-middleware
  ;; TODO Transactions for sql storage
  [rrmp/parameters-middleware
   rrmm/format-middleware
   exception-middleware
   exception-logger
   rrc/coerce-exceptions-middleware
   rrc/coerce-request-middleware
   rrc/coerce-response-middleware])

(defn make-app [router]
  (ring/ring-handler
   router
   (ring/routes
    (ring/redirect-trailing-slash-handler)
    (ring/create-default-handler))))

(def m-decoder
  "Muuntaja decoder used to parse response bodies"
  (make-muuntaja))

(defn parse-body
  "Parses response body according to content type.  Throws an exception if 
   the content type is not supported."
  [resp]
  (assoc resp :body (mc/decode-response-body m-decoder resp)))

(defn new-build-id [idx]
  (str "build-" idx))

(defn- check-avail-credits! [{st :storage} build]
  (let [avail (st/calc-available-credits st (:customer-id build))]
    (when (or (nil? avail) (<= avail 0))
      (throw (ex-info "Customer does not have available credits"
                      {:build build})))))

(defn run-build-async
  "Starts the build in a new thread"
  [rt build]
  (let [runner (rt/runner rt)
        report-error (fn [ex]
                       (log/error "Unable to start build:" ex)
                       (rt/post-events rt (b/build-end-evt
                                           (-> build
                                               (assoc :status :error
                                                      :message (ex-message ex))))))]
    (md/future
      (try
        (check-avail-credits! rt build)
        (rt/post-events rt (ec/make-event :build/pending
                                          :build (b/build->evt build)
                                          :sid (b/sid build)))
        ;; Catch both the deferred error, or the direct exception, because both
        ;; can be thrown here.
        (-> (runner build rt)
            (md/catch report-error))
        (catch Exception ex
          (report-error ex))))))

(defn crypto-iv
  "Looks up crypto initialization vector for the customer associated with the
   request.  If no crypto record is found, one is generated."
  ([st cust-id]
     (if-let [crypto (st/find-crypto st cust-id)]
       (:iv crypto)
       (let [iv (v/generate-iv)]
         (log/debug "No crypto record found for customer" cust-id ", generating a new one")
         (when (st/save-crypto st {:customer-id cust-id
                                   :iv iv})
           iv))))
  ([req]
   (let [cust-id (customer-id req)
         st (req->storage req)]
     (crypto-iv st cust-id))))

(defn find-ssh-keys
  "Finds and decrypts ssh keys for the given repo"
  [st vault repo]
  (let [cust-id (:customer-id repo)
        iv (crypto-iv st cust-id)]
    (->> (st/find-ssh-keys st cust-id)
         (lbl/filter-by-label repo)
         (map #(update % :private-key (partial p/decrypt vault iv))))))
