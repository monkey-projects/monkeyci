(ns monkey.ci.storage
  "Data storage functionality.  Next to basic storage implementations, this ns also contains
   a lot of functions for working with storage entities.  Many of these are overridden by
   implementation-specific functions, and so implementations here don't focus on efficiency.
   They are merely used in tests."
  (:require [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.ci
             [cuid :as cuid]
             [protocols :as p]
             [sid :as sid]]
            [monkey.ci.common.preds :as cp]
            [monkey.ci.storage.cached :as cached])
  (:import [java.io File PushbackReader]))

(def sid? sid/sid?)
(def ->sid sid/->sid)

(def new-id
  "Generates a new random id"
  cuid/random-cuid)

;; In-memory implementation, provider for testing/development purposes.
(defrecord MemoryStorage [store]
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

(defn transact [st f]
  (if (satisfies? p/Transactable st)
    (p/transact st f)
    (f st)))

(defmacro with-transaction
  "Runs body in transaction by binding a transactional storage object to `conn`.
   If the storage implementation does not support transactions, this just invokes
   the body while binding the original storage to `conn`."
  [st conn & body]
  `(transact ~st (fn [tx#]
                   (let [~conn tx#]
                     ~@body))))

(defn make-memory-storage []
  (->MemoryStorage (atom {})))

(defmulti make-storage (comp :type :storage))

(defmethod make-storage :default [_]
  nil)

(defmethod make-storage :memory [_]
  (log/info "Using memory storage (only for dev purposes!)")
  (make-memory-storage))

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
(defn global-sid
  ([type id]
   [global (name type) id])
  ([type]
   [global (name type)]))

(def org-sid (partial global-sid :orgs))

(defn save-org [s cust]
  (p/write-obj s (org-sid (:id cust)) cust))

(defn find-org [s id]
  (p/read-obj s (org-sid id)))

(def find-orgs
  "Fetches multiple orgs, without repos"
  (override-or
   [:org :find-multiple]
   (fn [s ids]
     (->> ids
          (distinct)
          (map (partial find-org s))))))

(def search-orgs
  "Searches orgs using given filter"
  (override-or
   [:org :search]
   (fn [s {:keys [id name]}]
     (cond
       id
       (->> [(find-org s id)]
            (remove nil?))
       
       name
       ;; Naive approach, should be overridden by implementations
       (->> (p/list-obj s (global-sid :orgs))
            (map (partial find-org s))
            (filter (comp #(cs/includes? % name) :name)))
       
       :else
       []))))

(def save-repo
  "Saves the repository by updating the org it belongs to"
  (override-or
   [:repo :save]
   (fn [s {:keys [org-id id] :as r}]
     (-> (update-obj s (org-sid org-id) assoc-in [:repos id] r)
         ;; Return repo sid
         (conj id)))))

(def find-repo
  "Reads the repo, as part of the org object's projects"
  (override-or
   [:repo :find]
   (fn 
     [s [cust-id id]]
     (some-> (find-org s cust-id)
             (get-in [:repos id])
             (assoc :org-id cust-id)))))

(def update-repo
  "Applies `f` to the repo with given sid"
  (override-or
   [:repo :update]
   (fn [s [cust-id repo-id] f & args]
     (apply update-obj s (org-sid cust-id) update-in [:repos repo-id] f args))))

(declare list-build-ids)
(declare builds)
(declare webhook-sid)
(declare find-webhook)

(def delete-repo
  "Deletes repository with given sid, including all builds"
  (override-or
   [:repo :delete]
   (fn [s [cust-id repo-id :as sid]]
     (when (some? (update-obj s (org-sid cust-id) update :repos dissoc repo-id))
       ;; Delete all builds
       (->> (list-build-ids s sid)
            (map (comp sid/->sid (partial concat [builds] sid) vector))
            (map (partial p/delete-obj s))
            (doall))
       ;; Delete webhooks
       (->> (p/list-obj s (webhook-sid))
            (map (partial find-webhook s))
            (filter (comp (partial = sid) (juxt :org-id :repo-id)))
            (map (comp (partial p/delete-obj s) webhook-sid :id))
            (doall))
       true))))

(def list-repo-display-ids
  "Lists all display ids for the repos for given org"
  (override-or
   [:repo :list-display-ids]
   (fn [s cust-id]
     (->> (find-org s cust-id)
          :repos
          keys))))

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
   (fn [s {:keys [org-id id github-id] :as r}]
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
       (when-let [gid (some-> (find-repo s sid)
                              :github-id)]
         ;; Remove it from the list of watched repos for the stored github id
         (update-obj s (watched-sid gid) (comp vec (partial remove (partial = sid))))
         ;; Clear github id
         (update-repo s sid dissoc :github-id)
         true)))))

(def webhook-sid (partial global-sid :webhooks))

(defn save-webhook [s details]
  (p/write-obj s (webhook-sid (:id details)) details))

(def ^:deprecated save-webhook-details save-webhook)

(defn find-webhook [s id]
  (p/read-obj s (webhook-sid id)))

(def ^:deprecated find-details-for-webhook find-webhook)

(defn delete-webhook [s id]
  (p/delete-obj s (webhook-sid id)))

(def find-webhooks-for-repo
  (override-or
   [:repo :find-webhooks]
   (fn [s sid]
     (->> (p/list-obj s (webhook-sid))
          (map (partial find-webhook s))
          (filter (comp (partial = sid) (juxt :org-id :repo-id)))
          (map (comp (partial find-webhook s) :id))
          (doall)))))

(def bb-webhooks :bb-webhooks)
(def bb-webhook-sid (partial global-sid bb-webhooks))

(defn save-bb-webhook
  "Stores bitbucket webhook information.  This links a Bitbucket native webhook uuid
   to a MonkeyCI webhook."
  [s wh]
  (p/write-obj s (bb-webhook-sid (:id wh)) wh))

(defn find-bb-webhook
  [s id]
  (p/read-obj s (bb-webhook-sid id)))

(def search-bb-webhooks
  "Retrieves bitbucket webhook that match given filter, and adds org and repo ids."
  (let [wh-props #{:org-id :repo-id}]
    (override-or
     [:bitbucket :search-webhooks]
     (fn [s f]
       (letfn [(add-webhook [bb-wh]
                 (merge bb-wh (-> (find-webhook s (:webhook-id bb-wh))
                                  (select-keys [:org-id :repo-id]))))
               (matches-filter? [bb-wh]
                 (= f (select-keys bb-wh (keys f))))]
         (->> (p/list-obj s (bb-webhook-sid))
              (map (partial find-bb-webhook s))
              (map add-webhook)
              (filter matches-filter?)))))))

(def find-bb-webhook-for-webhook
  "Retrieves bitbucket webhook given an internal webhook id"
  (override-or
   [:bitbucket :find-for-webhook]
   (fn [s wh-id]
     (some-> (search-bb-webhooks s {:webhook-id wh-id})
             (first)
             (dissoc :org-id :repo-id)))))

(def builds "builds")
(def build-sid-keys [:org-id :repo-id :build-id])
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
  (p/obj-exists? s (into [builds] sid)))

(defn legacy-build-exists?
  "Similar to `build-exists?` but for legacy builds that consist of metadata and 
   result entities."
  [s sid]
  (p/obj-exists? s (build-metadata-sid sid)))

(defn find-build
  "Finds build by sid"
  [s sid]
  (when sid
    ;; TODO Remove this legacy stuff after a while
    (if (legacy-build-exists? s sid)
      (-> (find-build-metadata s sid)
          (merge (find-build-results s sid))
          (assoc :legacy? true))
      (p/read-obj s (into [builds] sid)))))

(defn save-build
  "Creates or updates the build entity. without the jobs."
  [s build]
  (let [get-jobs (comp :jobs :script)
        orig (find-build s (ext-build-sid build))
        upd (cond-> build
              orig
              (update :script assoc :jobs (get-jobs orig)))]
    (p/write-obj s (build-sid build) upd)))

(defn- update-build
  "Atomically updates build by retrieving it, applying `f` to it, and then saving it back"
  [s sid f & args]
  (when-let [b (find-build s sid)]
    (p/write-obj s (into [builds] sid) (apply f b args))))

(defn list-build-ids
  "Lists the ids of the builds for given repo sid"
  [s sid]
  (p/list-obj s (into [builds] sid)))

(def list-builds
  "Lists all builds for the repo, and fetches the build details, similar to `find-build`
   but does not contain the job details."
  (override-or
   [:build :list]
   (fn [s sid]
     ;; Will be slow for large number of builds
     (->> (list-build-ids s sid)
          (map (comp (partial find-build s) (partial conj (->sid sid))))))))

(def find-latest-build
  "Retrieves the latest build for the repo"
  (override-or
   [:build :find-latest]
   (fn [s sid]
     (->> (list-build-ids s sid)
          (sort)   ; This assumes the build name is time-based
          (last)
          (conj (->sid sid))
          (find-build s)))))

(def find-latest-builds
  "Retrieves all latest builds for all repos for the org"
  (override-or
   [:org :find-latest-builds]
   (fn [s cust-id]
     (let [cust (find-org s cust-id)]
       (->> cust
            :repos
            vals
            (map (comp (partial vector cust-id) :id))
            (map (partial find-latest-build s)))))))

(def find-latest-n-builds
  "Retrieves the latest `n` builds, over all repos for the org."
  (override-or
   [:org :find-latest-n-builds]
   (fn [s cust-id n]
     (let [cust (find-org s cust-id)]
       (->> cust
            :repos
            vals
            (map (comp (partial vector cust-id) :id))
            (mapcat (partial list-builds s))
            (sort-by :start-time)
            (reverse)
            (take n))))))

(def list-builds-since
  "Retrieves all builds for org since the given timestamp"
  (override-or
   [:build :list-since]
   (fn [s cust-id ts]
     (letfn [(since? [b]
               (>= (:start-time b) ts))]
       (->> (find-org s cust-id)
            :repos
            keys
            (mapcat #(list-builds s [cust-id %]))
            (filter since?))))))

(def save-job
  "Saves the job in the build indicated by the sid"
  (override-or
   [:job :save]
   (fn [s build-sid job]
     (update-build s (->sid build-sid) assoc-in [:script :jobs (:id job)] job))))

(def find-job
  "Finds job by it's sid, which is the build sid with the job id added"
  (override-or
   [:job :find]
   (fn [s job-sid]
     (some-> (find-build s (sid/->sid (take 3 job-sid)))
             (get-in [:script :jobs (nth job-sid 3)])))))

(defn params-sid [org-id & [param-id]]
  ;; All parameters for a org are stored together
  (cond-> ["build-params" org-id]
    param-id (conj param-id)))

(defn find-params [s cust-id]
  (p/read-obj s (params-sid cust-id)))

(defn save-params
  "Saves all org parameters at once"
  [s cust-id p]
  (p/write-obj s (params-sid cust-id) p))

(def find-param
  "Retrieves a single parameter by sid"
  (override-or
   [:param :find]
   (fn [s [_ cust-id param-id]]
     (->> (find-params s cust-id)
          (filter (cp/prop-pred :id param-id))
          (first)))))

(def save-param
  "Saves a single org parameter"
  (override-or
   [:param :save]
   (fn [s {:keys [org-id] :as p}]
     (let [all (find-params s org-id)
           match (->> all
                      (filter (cp/prop-pred :id (:id p)))
                      (first))]
       (when (save-params
              s
              org-id
              (if match
                (replace {match p} all)
                (conj (vec all) p)))
         (params-sid org-id (:id p)))))))

(def delete-param
  "Deletes a single parameter set by sid"
  (override-or
   [:param :delete]
   (fn [s [_ org-id param-id]]
     (let [all (find-params s org-id)
           matcher (cp/prop-pred :id param-id)
           exists? (some matcher all)]
       (when exists?
         (save-params
          s
          org-id
          (remove matcher all))
         true)))))

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

(def find-user-by-type
  "Find user by type id (e.g. github)"
  (override-or
   [:user :find-by-type]
   (fn [s id]
     (when id
       (p/read-obj s (user-sid id))))))

(def find-user
  "Find user by cuid"
  (override-or
   [:user :find]
   (fn [s id]
     ;; Very slow for many users.  Use dedicated query in override.
     (letfn [(find-typed-users [t]
               (->> (p/list-obj s [global users t])
                    (map #(find-user-by-type s [(keyword t) %]))))]
       (when id
         (->> (p/list-obj s [global users])
              (mapcat find-typed-users)
              (filter (comp (partial = id) :id))
              (first)))))))

(def list-user-orgs
  (override-or
   [:user :orgs]
   (fn [s id]
     (some->> id
              (find-user s)
              :orgs
              (map (partial find-org s))))))

(def join-requests "join-requests")
(def join-request-sid (partial global-sid (keyword join-requests)))

(def save-join-request
  (override-or
   [:join-request :save]
   (fn [s req]
     (p/write-obj s (join-request-sid (:id req)) req))))

(def find-join-request
  (override-or
   [:join-request :find]
   (fn [s id]
     (p/read-obj s (join-request-sid id)))))

(defn- list-join-requests [s f]
  (->> (p/list-obj s (join-request-sid))
       (map (partial find-join-request s))
       (filter f)))

(def list-user-join-requests
  "Retrieves all org join requests for that user"
  (override-or
   [:join-request :list-user]
   #(list-join-requests %1 (comp (partial = %2) :user-id))))

(def list-org-join-requests
  "Retrieves all org join requests for that org"
  (override-or
   [:join-request :list-org]
   #(list-join-requests %1 (comp (partial = %2) :org-id))))

(def delete-join-request
  (override-or
   [:join-request :delete]
   (fn [s id]
     (p/delete-obj s (join-request-sid id)))))

(def find-next-build-idx
  "Retrieves the next integer build index to use.  This is supposed to be the highest
   build index + 1."
  (override-or
   [:repo :find-next-build-idx]
   (fn [s repo-sid]
     (or (some->> (list-builds s repo-sid)
                  (map :idx)
                  sort
                  last
                  inc)
         1))))

(def email-registrations :email-registrations)
(def email-registration-sid (partial global-sid email-registrations))

(defn save-email-registration [s reg]
  (p/write-obj s (email-registration-sid (:id reg)) reg))

(defn find-email-registration [s id]
  (p/read-obj s (email-registration-sid id)))

(def list-email-registrations
  (override-or
   [:email-registration :list]
   (fn [s]
     (->> (p/list-obj s (email-registration-sid))
          (map (partial find-email-registration s))))))

(def find-email-registration-by-email
  (override-or
   [:email-registration :find-by-email]
   (fn [s email]
     (->> (list-email-registrations s)
          (filter (comp (partial = email) :email))
          (first)))))

(defn delete-email-registration [s id]
  (p/delete-obj s (email-registration-sid id)))

(def org-credits :org-credits)
(def org-credit-sid (partial global-sid org-credits))

(defn save-org-credit [s cred]
  (p/write-obj s (org-credit-sid (:id cred)) cred))

(defn find-org-credit [s id]
  (p/read-obj s (org-credit-sid id)))

(def list-org-credits
  (override-or
   [:org :list-credits]
   (fn [s cust-id]
     (->> (p/list-obj s (org-credit-sid))
          (map (partial find-org-credit s))
          (filter (cp/prop-pred :org-id cust-id))))))

(def list-org-credits-since
  "Lists all org credits for the org since given timestamp.  
   This includes those without a `from-time`."
  (override-or
   [:org :list-credits-since]
   (fn [s cust-id ts]
     (->> (list-org-credits s cust-id)
          (filter (every-pred (cp/prop-pred :org-id cust-id)
                              (comp (some-fn nil? (partial <= ts)) :from-time)))))))

(def credit-subscriptions :credit-subscriptions)
(defn credit-sub-sid [& parts]
  (into [global (name credit-subscriptions)] parts))

(defn save-credit-subscription [s cs]
  (p/write-obj s (credit-sub-sid (:org-id cs) (:id cs)) cs))

(defn find-credit-subscription [s sid]
  (p/read-obj s (apply credit-sub-sid sid)))

(defn delete-credit-subscription [s sid]
  (p/delete-obj s (apply credit-sub-sid sid)))

(def list-org-credit-subscriptions
  (override-or
   [:org :list-credit-subscriptions]
   (fn [st cust-id]
     (let [sid (credit-sub-sid cust-id)]
       (->> (p/list-obj st sid)
            (map (partial vector cust-id))
            (map (partial find-credit-subscription st)))))))

(def list-active-credit-subscriptions
  "Lists all active credit subscriptions at given timestamp"
  (override-or
   [:credit :list-active-subscriptions]
   (fn [s at]
     (letfn [(active? [{:keys [valid-from valid-until]}]
               (and 
                (<= valid-from at)
                (or (nil? valid-until) (< at valid-until))))]
       (->> (p/list-obj s (credit-sub-sid))
            (mapcat (partial list-org-credit-subscriptions s))
            (filter active?))))))

(def credit-consumptions :credit-consumptions)
(defn credit-cons-sid [& parts]
  (into [global (name credit-consumptions)] parts))

(defn save-credit-consumption [s cs]
  (p/write-obj s (credit-cons-sid (:org-id cs) (:id cs)) cs))

(defn find-credit-consumption [s sid]
  (p/read-obj s sid))

(def list-org-credit-consumptions
  (override-or
   [:org :list-credit-consumptions]
   (fn [st cust-id]
     (let [sid (credit-cons-sid cust-id)]
       (->> (p/list-obj st sid)
            (map (partial conj sid))
            (map (partial find-credit-consumption st)))))))

(def list-org-credit-consumptions-since
  (override-or
   [:org :list-credit-consumptions-since]
   (fn [st cust-id since]
     (->> (list-org-credit-consumptions st cust-id)
          (filter (comp (partial <= since) :consumed-at))))))

(defn- sum-amount [e]
  (->> e
       (map :amount)
       (reduce + 0M)))

(def list-available-credits
  "Lists all available org credits.  These are the credits that have not been fully
   consumed, i.e. the difference between the amount and the sum of all consumptions linked
   to the credit is positive."
  (override-or
   [:org :list-available-credits]
   (fn [s cust-id]
     (let [consm (->> (list-org-credit-consumptions s cust-id)
                      (group-by :credit-id))
           avail? (fn [{:keys [id amount]}]
                    (->> (get consm id)
                         (sum-amount)
                         (- amount)
                         pos?))]
       (->> (list-org-credits s cust-id)
            (filter avail?))))))

(def calc-available-credits
  "Calculates the available credits for the org.  Basically this is the
   amount of provisioned credits, substracted by the consumed credits."
  (override-or
   [:org :get-available-credits]
   (fn [s cust-id]
     ;; Naive implementation: sum up all provisioned credits and all
     ;; credits from all builds
     (let [avail (->> (list-org-credits s cust-id)
                      (sum-amount))
           used  (->> (list-org-credit-consumptions s cust-id)
                      (sum-amount))]
       (- avail used)))))

(def crypto :crypto)
(defn crypto-sid [& parts]
  (into [global (name crypto)] parts))

(defn save-crypto [st crypto]
  (p/write-obj st (crypto-sid (:org-id crypto)) crypto))

(defn find-crypto [st cust-id]
  (p/read-obj st (crypto-sid cust-id)))

(def sysadmin :sysadmin)
(defn sysadmin-sid [& parts]
  (into [global (name sysadmin)] parts))

(defn save-sysadmin [st sysadmin]
  (p/write-obj st (sysadmin-sid (:user-id sysadmin)) sysadmin))

(defn find-sysadmin [st user-id]
  (p/read-obj st (sysadmin-sid user-id)))

(def invoice :invoice)
(defn invoice-sid [& parts]
  (into [global (name invoice)] parts))

(defn save-invoice [st invoice]
  (p/write-obj st (invoice-sid (:org-id invoice) (:id invoice)) invoice))

(defn find-invoice [st sid]
  (p/read-obj st (apply invoice-sid sid)))

(def list-invoices-for-org
  (override-or
   [:invoice :list-for-org]
   (fn [st cust-id]
     (->> (p/list-obj st (invoice-sid cust-id))
          (map (partial vector cust-id))
          (map (partial find-invoice st))))))


(def runner-details :runner-details)
(defn runner-details-sid [build-sid]
  (into [global (name runner-details)] build-sid))

(defn save-runner-details
  "Saves runner details for the given build sid"
  [st sid details]
  (p/write-obj st (runner-details-sid sid) details))

(defn find-runner-details
  "Retrieves runner details for given build sid"
  [st sid]
  (p/read-obj st (runner-details-sid sid)))

(def queued-task :queued-task)
(defn queued-task-sid [& [task-id]]
  (cond-> [global (name queued-task)]
    task-id (conj task-id)))

(defn save-queued-task
  "Saves retry task, which is used by the dispatcher to reschedule tasks that could not be
   executed immediately."
  [st task]
  (p/write-obj st (queued-task-sid (:id task)) task))

(defn find-queued-task
  [st id]
  (p/read-obj st (queued-task-sid id)))

(def list-queued-tasks
  (override-or
   [:queued-task :list]
   (fn [st]
     (->> (p/list-obj st (queued-task-sid))
          (map (partial find-queued-task st))))))

(defn delete-queued-task
  [st id]
  (p/delete-obj st (queued-task-sid id)))

(def job-event :job-event)
(defn job-event-sid [parts]
  (into [global (name job-event)] parts))

(def job-event->sid (juxt :org-id :repo-id :build-id :job-id (comp str :time)))

(defn save-job-event [st evt]
  (p/write-obj st (job-event-sid (job-event->sid evt)) evt))

(def list-job-events
  (override-or
   [:job :list-events]
   (fn [st job-sid]
     (->> (p/list-obj st (job-event-sid job-sid))
          (map (comp job-event-sid (partial conj job-sid)))
          (map (partial p/read-obj st))
          (sort-by :time)))))
