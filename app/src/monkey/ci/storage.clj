(ns monkey.ci.storage
  "Data storage functionality"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [config :as c]
             [cuid :as cuid]
             [protocols :as p]
             [runtime :as rt]
             [sid :as sid]
             [utils :as u]]
            [monkey.ci.storage.cached :as cached])
  (:import [java.io File PushbackReader]))

(def sid? sid/sid?)
(def ->sid sid/->sid)

(def new-id
  "Generates a new random id"
  cuid/random-cuid)

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

(defn- override-or
  "Invokes the override function in the storage at given path, or calls the
   fallback function.  This is used by storage implementations to override the
   default behaviour, for performance reasons."
  [ovr-path fallback]
  (fn [s & args]
    (let [h (get-in s (concat [:overrides] ovr-path) fallback)]
      (apply h s args))))

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

(def save-repo
  "Saves the repository by updating the customer it belongs to"
  (override-or
   [:repo :save]
   (fn [s {:keys [customer-id id] :as r}]
     (-> (update-obj s (customer-sid customer-id) assoc-in [:repos id] r)
         ;; Return repo sid
         (conj id)))))

(def find-repo
  "Reads the repo, as part of the customer object's projects"
  (override-or
   [:repo :find]
   (fn 
     [s [cust-id id]]
     (some-> (find-customer s cust-id)
             (get-in [:repos id])
             (assoc :customer-id cust-id)))))

(def update-repo
  "Applies `f` to the repo with given sid"
  (override-or
   [:repo :update]
   (fn [s [cust-id repo-id] f & args]
     (apply update-obj s (customer-sid cust-id) update-in [:repos repo-id] f args))))

(def ext-repo-sid (partial take-last 2))
(def watched-sid (comp (partial global-sid :watched) str))

(def find-watched-github-repos
  "Looks up all watched repos with the given github id"
  (override-or
   [:watched-github-repos :find]
   (fn [s github-id]
     (->> (p/read-obj s (watched-sid github-id))
          (map (partial find-repo s))))))

(def watch-github-repo
  "Creates necessary records to start watching a github repo.  Creates the
   repo entity and returns it."
  (override-or
   [:watched-github-repos :watch]
   (fn [s {:keys [customer-id id github-id] :as r}]
     (let [repo-sid (save-repo s r)]
       ;; Add the repo sid to the list of watched repos for the github id
       (update-obj s (watched-sid github-id) (fnil conj []) (ext-repo-sid repo-sid))
       repo-sid))))

(def unwatch-github-repo
  "Removes the records to stop watching the repo.  The entity will still 
   exist, so any past builds can be looked up."
  (override-or
   [:watched-github-repos :unwatch]
   (fn [s sid]
     (when-let [repo (find-repo s sid)]
       (when-let [gid (:github-id repo)]
         ;; Remove it from the list of watched repos for the stored github id
         (update-obj s (watched-sid gid) (comp vec (partial remove (partial = sid))))
         ;; Clear github id
         (update-repo s sid dissoc :github-id)
         true)))))

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

;;; Deprecated build functions.  These are no longer split up between metadata and results.

(defn- ^:deprecated build-sub-sid [obj p]
  (conj (build-sid obj) p))

(defn- ^:deprecated sub-sid-builder
  "Creates a fn that is able to build a sid with given prefix and suffix value."
  [f]
  (fn [c]
    (if (sid? c)
      (vec (concat [builds] c [f]))
      (build-sub-sid c f))))

(def ^:deprecated build-metadata-sid (sub-sid-builder "metadata"))
(def ^:deprecated build-results-sid  (sub-sid-builder "results"))

(defn ^:deprecated create-build-metadata
  ([s sid md]
   (p/write-obj s (build-metadata-sid sid) md))
  ([s md]
   (create-build-metadata s md md)))

(defn ^:deprecated find-build-metadata
  "Reads the build metadata given the build coordinates (required to build the path)"
  [s sid]
  (p/read-obj s (build-metadata-sid sid)))

(defn ^:deprecated save-build-results [s sid r]
  (p/write-obj s (build-results-sid sid) r))

(defn ^:deprecated find-build-results
  "Reads the build results given the build coordinates"
  [s sid]
  (p/read-obj s (build-results-sid sid)))

(defn ^:deprecated patch-build-results
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
  (p/obj-exists? s (concat [builds] sid)))

(defn legacy-build-exists?
  "Similar to `build-exists?` but for legacy builds that consist of metadata and 
   result entities."
  [s sid]
  (p/obj-exists? s (build-metadata-sid sid)))

(defn save-build
  "Creates or updates the build entity"
  [s build]
  (p/write-obj s (build-sid build) build))

(defn find-build
  "Finds build by sid"
  [s sid]
  (when sid
    (p/read-obj s (concat [builds] sid))))

(defn list-builds
  "Lists the ids of the builds for given repo sid"
  [s sid]
  (p/list-obj s (concat [builds] sid)))

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
  (when id
    (p/read-obj s (user-sid id))))
