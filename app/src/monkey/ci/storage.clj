(ns monkey.ci.storage
  "Data storage functionality"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [config :as c]
             [protocols :as p]
             [runtime :as rt]
             [utils :as u]]
            [monkey.ci.storage.cached :as cached])
  (:import [java.io File PushbackReader]))

(def sid? vector?)
(def ->sid vec)

(def new-id
  "Generates a new random id"
  (comp str random-uuid))

;; In-memory implementation, provider for testing/development purposes.
;; Must be a type, not a record otherwise reitit sees it as a map.
(deftype MemoryStorage [store]
  p/Storage
  (read-obj [_ loc]
    (get-in @store loc))
  
  (write-obj [_ loc obj]
    (swap! store assoc-in loc obj)
    loc)

  (obj-exists? [_ loc]
    (some? (get-in @store loc)))

  (delete-obj [this loc]
    (if (p/obj-exists? this loc)
      (do
        (swap! store mc/dissoc-in loc)
        true)
      false))

  (list-obj [_ loc]
    (keys (get-in @store loc))))

(defn make-memory-storage []
  (->MemoryStorage (atom {})))

(defmulti make-storage (comp :type :storage))

(defmethod make-storage :memory [_]
  (log/info "Using memory storage (only for dev purposes!)")
  (make-memory-storage))

(defmulti normalize-storage-config (comp :type :storage))

(defmethod normalize-storage-config :default [conf]
  conf)

(defmethod c/normalize-key :storage [k conf]
  (c/normalize-typed k conf normalize-storage-config))

(defmethod rt/setup-runtime :storage [conf _]
  ;; Always make it cached, unless it's memory storage
  (cond-> (make-storage conf)
    (not= :memory (get-in conf [:storage :type])) (cached/->CachedStorage (make-memory-storage))))

;;; Higher level functions

(defn- update-obj
  "Reads the object at given `sid`, and then applies the updater to it, with args"
  [s sid updater & args]
  (let [obj (p/read-obj s sid)]
    (p/write-obj s sid (apply updater obj args))))

(def global "global")
(defn global-sid [type id]
  [global (name type) id])

(def customer-sid (partial global-sid :customers))

(defn save-customer [s cust]
  (p/write-obj s (customer-sid (:id cust)) cust))

(defn find-customer [s id]
  (p/read-obj s (customer-sid id)))

(defn save-repo
  "Saves the repository by updating the customer it belongs to"
  [s {:keys [customer-id id] :as r}]
  (update-obj s (customer-sid customer-id) assoc-in [:repos id] r))

(defn find-repo
  "Reads the repo, as part of the customer object's projects"
  [s [cust-id id]]
  (some-> (find-customer s cust-id)
          (get-in [:repos id])))

(def webhook-sid (partial global-sid :webhooks))

(defn save-webhook-details [s details]
  (p/write-obj s (webhook-sid (:id details)) details))

(defn find-details-for-webhook [s id]
  (p/read-obj s (webhook-sid id)))

(def builds "builds")
(def build-sid-keys [:customer-id :repo-id :build-id])
;; Build sid, for external representation
(def ext-build-sid (apply juxt build-sid-keys))
(def build-sid (comp (partial into [builds])
                     ext-build-sid))

(defn- build-sub-sid [obj p]
  (conj (build-sid obj) p))

(defn- sub-sid-builder
  "Creates a fn that is able to build a sid with given prefix and suffix value."
  [f]
  (fn [c]
    (if (sid? c)
      (vec (concat [builds] c [f]))
      (build-sub-sid c f))))

(def build-metadata-sid (sub-sid-builder "metadata"))
(def build-results-sid  (sub-sid-builder "results"))

(defn create-build-metadata
  ([s sid md]
   (p/write-obj s (build-metadata-sid sid) md))
  ([s md]
   (create-build-metadata s md md)))

(defn find-build-metadata
  "Reads the build metadata given the build coordinates (required to build the path)"
  [s sid]
  (p/read-obj s (build-metadata-sid sid)))

(defn save-build-results [s sid r]
  (p/write-obj s (build-results-sid sid) r))

(defn find-build-results
  "Reads the build results given the build coordinates"
  [s sid]
  (p/read-obj s (build-results-sid sid)))

(defn patch-build-results
  "Finds the build result with given sid, then applies `f` to it with arguments
   and saves the return value back into the result."
  [st sid f & args]
  (let [r (find-build-results st sid)]
    (->> (apply f r args)
         (save-build-results st sid))))

(defn build-exists?
  "Checks efficiently if the build exists.  This is cheaper than trying to fetch it
   and checking if the result is `nil`."
  [s sid]
  (p/obj-exists? s (build-metadata-sid sid)))

(defn list-builds
  "Lists the ids of the builds for given repo sid"
  [s sid]
  (p/list-obj s (concat [builds] sid)))

(defn ^:deprecated legacy-params-sid [sid]
  ;; Prepend params prefix, but also store at "params" leaf
  (vec (concat ["params"] sid ["params"])))

(defn ^:deprecated save-legacy-params
  "Stores build parameters.  This can be done on customer, project or repo level.
   The `sid` is a vector that determines on which level the information is stored."
  [s sid p]
  (p/write-obj s (legacy-params-sid sid) p))

(defn ^:deprecated find-legacy-params
  "Loads parameters on the given level.  This does not automatically include the
   parameters of higher levels."
  [s sid]
  (p/read-obj s (legacy-params-sid sid)))

(defn params-sid [customer-id]
  ;; All parameters for a customer are stored together
  ["build-params" customer-id])

(defn find-params [s cust-id]
  (p/read-obj s (params-sid cust-id)))

(defn save-params [s cust-id p]
  (p/write-obj s (params-sid cust-id) p))

(defn ssh-keys-sid [cust-id]
  ["ssh-keys" cust-id])

(defn save-ssh-keys [s cust-id key]
  (p/write-obj s (ssh-keys-sid cust-id) key))

(defn find-ssh-keys [s cust-id]
  (p/read-obj s (ssh-keys-sid cust-id)))

(def users "users")

(defn user-sid [[type id]]
  [global users (name type) (str id)])

(def user->sid (comp user-sid (juxt :type :type-id)))

(defn save-user [s u]
  (p/write-obj s (user->sid u) u))

(defn find-user [s id]
  (p/read-obj s (user-sid id)))
