(ns monkey.ci.web.common
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [monkey.ci
             [labels :as lbl]
             [storage :as st]
             [time :as t]]
            [muuntaja.core :as mc]
            [reitit.ring :as ring]
            [ring.util.response :as rur]
            [schema.core :as s]))

(def not-empty-str (s/constrained s/Str not-empty))
(def Id not-empty-str)
(def Name not-empty-str)

(def body
  "Retrieves request body"
  (comp :body :parameters))

(def org-id (comp :org-id :path :parameters))

(def repo-sid
  "Retrieves repo sid from the request"
  (comp (juxt :org-id :repo-id)
        :path
        :parameters))

(def build-sid
  "Retrieves build sid from the request"
  (comp st/ext-build-sid
        :path
        :parameters))

(defn generic-routes
  "Generates generic entity routes.  If child routes are given, they are added
   as additional routes after the full path."
  [{:keys [getter id-key
           creator new-schema
           updater update-schema
           searcher search-schema
           deleter delete-schema
           child-routes]}]
  [["" (cond-> {:post {:handler creator
                       :parameters {:body new-schema}}}
         searcher (assoc :get {:handler searcher
                               :parameters {:query search-schema}}))]
   [(str "/" id-key)
    {:parameters {:path {id-key Id}}}
    (cond-> [["" (cond-> {:get {:handler getter}}
                   updater (assoc :put
                                  {:handler updater
                                   :parameters {:body update-schema}})
                   deleter (assoc :delete
                                  (cond-> {:handler deleter}
                                    delete-schema (assoc-in [:parameters :body] delete-schema))))]]
      child-routes (concat child-routes))]])

(defn error-response
  ([error-msg status]
   (-> (rur/response {:error error-msg})
       (rur/status status)))
  ([error-msg]
   (error-response error-msg 400)))

;; Reitit rewrites records in the data to hashmaps, so wrap it in a type
(deftype RuntimeWrapper [runtime])

(def route-data (comp :data :reitit.core/match))

(defn req->rt
  "Gets the runtime from the request"
  [req]
  (some-> (route-data req)
          ::runtime
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

(def req->mailman
  "Retrieves mailman component from request"
  #(from-rt % :mailman))

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

(def drop-ids (partial map #(dissoc % :org-id)))

(defn get-list-for-org
  "Utility function that uses the `finder` to fetch a list of things from storage
   using the org id from the request.  Returns the result as a http response."
  [finder req]
  (-> (req->storage req)
      (finder (org-id req))
      (or [])
      (rur/response)))

(defn update-for-org
  "Uses the `updater` to save the request body using the org id.  Returns the 
   body with an id as a http response."
  [updater req]
  (let [assign-id (fn [{:keys [id] :as obj}]
                    (cond-> obj
                      (nil? id) (assoc :id (st/new-id))))
        p (->> (body req)
               (map assign-id))]
    ;; TODO Allow patching values so we don't have to send back all secrets to client
    (when (updater (req->storage req) (org-id req) p)
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
      (->> (finder st (org-id req))
           (lbl/filter-by-label repo)
           (into [] tx)
           (rur/response))
      (rur/not-found {:message (format "Repository %s does not exist" sid)}))))

(defn gen-repo-display-id
  "Generates id from the object name.  It lists existing repository display ids
   and generates an id from the name.  If the display id is already taken, it adds
   an index."
  [st obj]
  (let [existing? (-> (:org-id obj)
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

(defn set-wh-invocation-time [st wh]
  (st/save-webhook st (assoc wh :last-inv-time (t/now))))
