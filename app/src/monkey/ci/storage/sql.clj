(ns monkey.ci.storage.sql
  "Storage implementation that uses an SQL database for persistence.  This namespace provides
   a layer on top of the entities namespace to perform the required queries whenever a 
   document is saved or loaded."
  (:require [clojure.set :as cset]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [medley.core :as mc]
            [monkey.ci.entities
             [bb-webhook :as ebbwh]
             [build :as eb]
             [core :as ec]
             [credit-cons :as eccon]
             [credit-subs :as ecsub]
             [org :as ecu]
             [org-credit :as ecc]
             [invoice :as ei]
             [job :as ej]
             [join-request :as jr]
             [migrations :as emig]
             [param :as eparam]
             [repo :as er]
             [ssh-key :as essh]
             [user :as eu]
             [webhook :as ewh]]
            [monkey.ci
             [labels :as lbl]
             [protocols :as p]
             [sid :as sid]
             [spec :as spec]
             [storage :as st]]
            [monkey.ci.spec.db-entities]
            [monkey.ci.spec.entities]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as conn])
  (:import com.zaxxer.hikari.HikariDataSource))

(def deleted? (fnil pos? 0))

(defn- drop-nil [m]
  (mc/filter-vals some? m))

(defn- db->labels [labels]
  (map #(select-keys % [:name :value]) labels))

(defn- id->cuid [x]
  (-> x
      (assoc :cuid (:id x))
      (dissoc :id)))

(defn- cuid->id [x]
  (-> x
      (assoc :id (:cuid x))
      (dissoc :cuid)))

(defn- repo->db
  "Converts the repository into an entity that can be sent to the database."
  [r org-id]
  (-> r
      (select-keys [:name :url :main-branch :github-id])
      (dissoc :id)
      (assoc :display-id (:id r)
             :org-id org-id)))

(defn- db->repo
  "Converts the repo entity (a db record) into a repository.  If `f` is provided,
   it is invoked to allow some processing on the resulting object."
  [re & [f]]
  (cond->
      (-> re
          (dissoc :cuid :org-id :display-id)
          (assoc :id (:display-id re))
          (mc/update-existing :labels db->labels)
          (drop-nil))
    f (f re)))

(defn- insert-repo-labels [conn labels re]
  (when-not (empty? labels)
    (->> labels
         (map #(assoc % :repo-id (:id re)))
         (ec/insert-repo-labels conn))))

(defn- update-repo-labels [conn labels]
  (doseq [l labels]
    (ec/update-repo-label conn l)))

(defn- delete-repo-labels [conn labels]
  (when-not (empty? labels)
    (ec/delete-repo-labels conn [:in :id (map :id labels)])))

(defn- sync-repo-labels [conn labels re]
  {:pre [(some? (:id re))]}
  (let [ex (ec/select-repo-labels conn (ec/by-repo (:id re)))
        {:keys [insert update delete] :as r} (lbl/reconcile-labels ex labels)]
    (log/debug "Reconciled labels" labels "into" r)
    (insert-repo-labels conn insert re)
    (update-repo-labels conn update)
    (delete-repo-labels conn delete)))

(defn- insert-repo [conn re repo]
  (let [re (ec/insert-repo conn re)]
    (insert-repo-labels conn (:labels repo) re)
    ;; Also create an initial repo idx record for build index calculation
    (ec/insert-repo-idx conn {:repo-id (:id re)
                              :next-idx 1})))

(defn- update-repo [conn re repo existing]
  (when (not= re existing)
    (let [re (merge existing re)]
      (ec/update-repo conn re)
      (sync-repo-labels conn (:labels repo) re))))

(defn- select-repo-id-by-sid [conn [org-id repo-id]]
  (er/repo-for-build-sid conn org-id repo-id))

(defn- upsert-repo [conn repo org-id]
  (spec/valid? :entity/repo repo)
  (let [re (repo->db repo org-id)]
    (spec/valid? :db/repo re)
    (if-let [existing (ec/select-repo conn [:and
                                            (ec/by-org org-id)
                                            (ec/by-display-id (:id repo))])]
      (update-repo conn re repo existing)
      (insert-repo conn re repo))))

(defn- upsert-repos [conn {:keys [repos]} org-id]
  (doseq [[_ r] repos]
    (upsert-repo conn r org-id)))

(defn- delete-repo [{:keys [conn]} sid]
  (when-let [repo-id (select-repo-id-by-sid conn sid)]
    ;; Other records are deleted by cascading
    (pos? (ec/delete-repos conn (ec/by-id repo-id)))))

(defn- select-repo-display-ids [{:keys [conn]} org-id]
  (er/repo-display-ids conn org-id))

(defn- org->db [org]
  (-> org
      (id->cuid)
      (select-keys [:cuid :name])))

(def db->org cuid->id)

(defn- db->org-with-repos [c]
  (letfn [(entities->repos [repos]
            (reduce-kv (fn [r _ v]
                         (assoc r (:display-id v) (db->repo v)))
                       {}
                       repos))]
    (-> c
        (db->org)
        (mc/update-existing :repos entities->repos))))

(defn- insert-org [conn org]
  (let [org-id (:id (ec/insert-org conn (org->db org)))]
    (upsert-repos conn org org-id)
    org))

(defn- update-org [conn org existing]
  (let [ce (org->db org)]
    (spec/valid? :db/customer ce)
    (when (not= ce existing)
      (ec/update-org conn (merge existing ce)))
    (upsert-repos conn org (:id existing))
    org))

(defn- upsert-org [conn org]
  (spec/valid? :entity/customer org)
  (if-let [existing (ec/select-org conn (ec/by-cuid (:id org)))]
    (update-org conn org existing)
    (insert-org conn org)))

(defn- select-org [conn cuid]
  (when cuid
    (some-> (ecu/org-with-repos conn (ec/by-cuid cuid))
            (db->org-with-repos))))

(defn- org-exists? [conn cuid]
  (some? (ec/select-org conn (ec/by-cuid cuid))))

(defn- delete-org [conn cuid]
  (when cuid
    (ec/delete-orgs conn (ec/by-cuid cuid))))

(defn- select-orgs
  "Finds orgs by filter"
  [{:keys [conn]} {:keys [id name]}]
  (let [query (cond
                id (ec/by-cuid id)
                ;; By default, this will use case insensitive search (depends on collation)
                name [:like :name (str "%" name "%")])]
    (->> (ec/select-orgs conn query)
         (map db->org-with-repos))))

(defn- select-orgs-by-id [{:keys [conn]} ids]
  (->> (ec/select-orgs conn [:in :cuid (distinct ids)])
       (map db->org)))

(defn- global-sid? [type sid]
  (= [st/global (name type)] (take 2 sid)))

(def org? (partial global-sid? :customers))
(def webhook? (partial global-sid? :webhooks))

(defn- global-sid->cuid [sid]
  (nth sid 2))

(defn- insert-webhook [conn wh]
  (if-let [repo-id (select-repo-id-by-sid conn [(:org-id wh) (:repo-id wh)])]
    (let [we {:cuid (:id wh)
              :repo-id repo-id
              :secret (:secret-key wh)}]
      (ec/insert-webhook conn we))
    (throw (ex-info "Repository does not exist" wh))))

(defn- update-webhook [conn wh existing])

(defn- upsert-webhook [conn wh]
  (spec/valid? :entity/webhook wh)
  (if-let [existing (ec/select-webhook conn (ec/by-cuid (:id wh)))]
    (update-webhook conn wh existing)
    (insert-webhook conn wh)))

(defn- select-webhook [conn cuid]
  (-> (ewh/select-webhooks-as-entity conn (ewh/by-cuid cuid))
      (first)))

(defn- select-repo-webhooks [{:keys [conn]} [org-id repo-id]]
  (ewh/select-webhooks-as-entity conn (ewh/by-repo org-id repo-id)))

(defn- delete-webhook [conn cuid]
  (ec/delete-webhooks conn (ec/by-cuid cuid)))

(defn- top-sid? [type sid]
  (and (= 2 (count sid))
       (= (name type) (first sid))))

(def ssh-key? (partial top-sid? :ssh-keys))

(defn- ssh-key->db [k]
  (-> k
      (id->cuid)
      (dissoc :org-id)))

(defn- insert-ssh-key [conn ssh-key org-id]
  (log/debug "Inserting ssh key:" ssh-key)
  (ec/insert-ssh-key conn (-> ssh-key
                              (ssh-key->db)
                              (assoc :org-id org-id))))

(defn- update-ssh-key [conn ssh-key existing]
  (log/debug "Updating ssh key:" ssh-key)
  (ec/update-ssh-key conn (merge existing (ssh-key->db ssh-key))))

(defn- upsert-ssh-key [conn org-id ssh-key]
  (spec/valid? :entity/ssh-key ssh-key)
  (if-let [existing (ec/select-ssh-key conn (ec/by-cuid (:id ssh-key)))]
    (update-ssh-key conn ssh-key existing)
    (insert-ssh-key conn ssh-key org-id)))

(defn- upsert-ssh-keys [conn org-cuid ssh-keys]
  (when (not-empty ssh-keys)
    (if-let [{org-id :id} (ec/select-org conn (ec/by-cuid org-cuid))]
      (doseq [k ssh-keys]
        (upsert-ssh-key conn org-id k))
      (throw (ex-info "Org not found when upserting ssh keys" {:org-id org-cuid})))
    ssh-keys))

(defn- select-ssh-keys [conn org-id]
  (essh/select-ssh-keys-as-entity conn org-id))

(def params? (partial top-sid? :build-params))

(defn- insert-param-values [conn values param-id]
  (when-not (empty? values)
    (->> values
         (map (fn [v]
                (-> (select-keys v [:name :value])
                    (assoc :params-id param-id))))
         (ec/insert-org-param-values conn))))

(defn- update-param-values [conn values]
  (doseq [pv values]
    (ec/update-org-param-value conn pv)))

(defn- delete-param-values [conn values]
  (when-not (empty? values)
    (ec/delete-org-param-values conn [:in :id (map :id values)])))

(defn- param->db [param org-id]
  (-> param
      (id->cuid)
      (select-keys [:cuid :description :label-filters])
      (assoc :org-id org-id)))

(defn- insert-param [conn param org-id]
  (let [{:keys [id]} (ec/insert-org-param conn (param->db param org-id))]
    (insert-param-values conn (:parameters param) id)))

(defn- update-param [conn param org-id existing]
  (ec/update-org-param conn (merge existing (param->db param org-id)))
  (let [ex-vals (ec/select-org-param-values conn (ec/by-params (:id existing)))
        r (lbl/reconcile-labels ex-vals (:parameters param))]
    (log/debug "Reconciled param values:" r)
    (insert-param-values conn (:insert r) (:id existing))
    (update-param-values conn (:update r))
    (delete-param-values conn (:delete r))))

(defn- upsert-param [conn param org-id]
  (spec/valid? :entity/customer-params param)
  (if-let [existing (ec/select-org-param conn (ec/by-cuid (:id param)))]
    (update-param conn param org-id existing)
    (insert-param conn param org-id)))

(defn- upsert-params [conn org-cuid params]
  (when-not (empty? params)
    (let [{org-id :id} (ec/select-org conn (ec/by-cuid org-cuid))]
      (doseq [p params]
        (upsert-param conn p org-id))
      params)))

(defn- select-params [conn org-id]
  ;; Select org params and values for org cuid
  (eparam/select-org-params-with-values conn org-id))

(defn- upsert-org-param [{:keys [conn]} {:keys [org-id] :as param}]
  (when-let [{db-id :id} (ec/select-org conn (ec/by-cuid org-id))]
    (upsert-param conn param db-id)
    (st/params-sid org-id (:id param))))

(defn- select-org-param [{:keys [conn]} [_ _ param-id]]
  (eparam/select-param-with-values conn param-id))

(defn- delete-org-param [{:keys [conn]} [_ _ param-id]]
  (pos? (ec/delete-org-params conn (ec/by-cuid param-id))))

(defn user? [sid]
  (and (= 4 (count sid))
       (= [st/global "users"] (take 2 sid))))

(defn- user->db [user]
  (-> user
      (id->cuid)
      (select-keys [:cuid :type :type-id :email])
      (mc/update-existing :type name)
      (mc/update-existing :type-id str)))

(defn- db->user [user]
  (-> user
      (cuid->id)
      (select-keys [:id :type :type-id :email])
      (mc/update-existing :type keyword)))

(defn- insert-user [conn user]
  (let [{:keys [id] :as ins} (ec/insert-user conn (user->db user))
        ids (ecu/org-ids-by-cuids conn (:orgs user))]
    (ec/insert-user-orgs conn id ids)
    ins))

(defn- update-user [conn user {user-id :id :as existing}]
  (when (ec/update-user conn (merge existing (user->db user)))
    ;; Update user/org links
    (let [existing-org (set (eu/select-user-org-cuids conn user-id))
          new-org (set (:orgs user))
          to-add (cset/difference new-org existing-org)
          to-remove (cset/difference existing-org new-org)]
      (ec/insert-user-orgs conn user-id (ecu/org-ids-by-cuids conn to-add))
      (when-not (empty? to-remove)
        (ec/delete-user-orgs conn [:in :org-id (ecu/org-ids-by-cuids conn to-remove)]))
      user)))

(defn- upsert-user [conn user]
  (if-let [existing (ec/select-user conn (ec/by-cuid (:id user)))]
    (update-user conn user existing)
    (insert-user conn user)))

(defn- select-user-by-filter [conn f]
  (when-let [r (ec/select-user conn f)]
    (let [org (eu/select-user-org-cuids conn (:id r))]
      (cond-> (db->user r)
        true (drop-nil)
        (not-empty org) (assoc :orgs org)))))

(defn- select-user-by-type [conn [type type-id]]
  (select-user-by-filter conn [:and
                               [:= :type type]
                               [:= :type-id type-id]]))

(defn- select-user [{:keys [conn]} id]
  (select-user-by-filter conn (ec/by-cuid id)))

(defn- select-user-orgs [{:keys [conn]} id]
  (->> (eu/select-user-orgs conn id)
       (map db->org-with-repos)))

(defn build? [sid]
  (and (= "builds" (first sid))
       (= 4 (count sid))))

(defn build-repo? [sid]
  (and (= "builds" (first sid))
       (= 3 (count sid))))

(defn- build->db [build]
  (-> build
      (select-keys [:status :start-time :end-time :idx :git :credits :source :message])
      (mc/update-existing :status name)
      ;; Drop some sensitive information
      (mc/update-existing :git dissoc :ssh-keys-dir)
      (mc/update-existing-in [:git :ssh-keys] (partial map #(select-keys % [:id :description])))
      (assoc :display-id (:build-id build)
             :script-dir (get-in build [:script :script-dir]))))

(defn- db->build [build]
  (-> build
      (select-keys [:status :start-time :end-time :idx :git :credits :source :message])
      (mc/update-existing :status keyword)
      (ec/start-time->int)
      (ec/end-time->int)
      (assoc :build-id (:display-id build)
             :script (select-keys build [:script-dir]))
      (update :credits (fnil int 0))
      (mc/assoc-some :org-id (:org-cuid build)
                     :repo-id (:repo-display-id build))
      (drop-nil)))

(defn- job->db [job]
  (-> job
      (select-keys [:status :start-time :end-time :credit-multiplier])
      (mc/update-existing :status (fnil name :error))
      (assoc :display-id (:id job)
             :details (dissoc job :id :status :start-time :end-time))))

(defn- db->job [job]
  (-> job
      (select-keys [:status :start-time :end-time])
      (merge (:details job))
      (mc/update-existing :status keyword)
      (assoc :id (:display-id job))
      (drop-nil)))

(defn- insert-build [conn build]
  (when-let [repo-id (er/repo-for-build-sid conn (:org-id build) (:repo-id build))]
    (let [{:keys [id] :as ins} (ec/insert-build conn (-> (build->db build)
                                                         (assoc :repo-id repo-id)))]
      ins)))

(defn- update-build [conn build existing]
  (ec/update-build conn (merge existing (build->db build)))  
  build)

(defn- upsert-build [conn build]
  ;; Fetch build by org cuild and repo and build display ids
  (if-let [existing (eb/select-build-by-sid conn (:org-id build) (:repo-id build) (:build-id build))]
    (update-build conn build existing)
    (insert-build conn build)))

(defn- select-jobs [conn build-id]
  (->> (ec/select-jobs conn (ec/by-build build-id))
       (map db->job)
       (map (fn [j] [(:id j) j]))
       (into {})))

(defn- hydrate-build
  "Fetches jobs related to the build"
  [conn [org-id repo-id] build]
  (let [jobs (select-jobs conn (:id build))]
    (cond-> (-> (db->build build)
                (assoc :org-id org-id
                       :repo-id repo-id)
                (update :script drop-nil)
                (drop-nil))
      (not-empty jobs) (assoc-in [:script :jobs] jobs))))

(defn- select-build [conn [org-id repo-id :as sid]]
  (when-let [build (apply eb/select-build-by-sid conn sid)]
    (hydrate-build conn sid build)))

(defn- select-repo-builds
  "Retrieves all builds and their details for given repository"
  [{:keys [conn]} [org-id repo-id]]
  (letfn [(add-ids [b]
            (assoc b
                   :org-id org-id
                   :repo-id repo-id))]
    ;; Fetch all build details, don't include jobs since we don't need them at this point
    ;; and they can become a very large dataset.
    (->> (eb/select-builds-for-repo conn org-id repo-id)
         (map db->build)
         (map add-ids))))

(defn build-exists? [conn sid]
  (some? (apply eb/select-build-by-sid conn sid)))

(defn- select-repo-build-ids [conn sid]
  (apply eb/select-build-ids-for-repo conn sid))

(defn- select-org-builds-since [{:keys [conn]} org-id ts]
  (->> (eb/select-builds-for-org-since conn org-id ts)
       (map db->build)))

(defn- select-latest-build [{:keys [conn]} [org-id repo-id]]
  (some-> (eb/select-latest-build conn org-id repo-id)
          (db->build)))

(defn- select-latest-org-builds [{:keys [conn]} org-id]
  (->> (eb/select-latest-builds conn org-id)
       (map db->build)))

(defn- select-latest-n-org-builds [{:keys [conn]} org-id n]
  (->> (eb/select-latest-n-builds conn org-id n)
       (map db->build)))

(defn- select-next-build-idx [{:keys [conn]} [org-id repo-id]]
  (er/next-repo-idx conn org-id repo-id))

(defn- insert-job [conn job build-sid]
  (when-let [build (apply eb/select-build-by-sid conn build-sid)]
    (ec/insert-job conn (-> job
                            (job->db)
                            (assoc :build-id (:id build))))
    build-sid))

(defn- update-job [conn job existing]
  (let [upd (-> existing
                (dissoc :org-cuid :repo-display-id :build-display-id)
                (merge (job->db job)))]
    (when (ec/update-job conn upd)
      ;; Return build sid
      ((juxt :org-cuid :repo-display-id :build-display-id) existing))))

(defn- upsert-job [{:keys [conn]} build-sid job]
  (if-let [existing (ej/select-by-sid conn (concat build-sid [(:id job)]))]
    (update-job conn job existing)
    (insert-job conn job build-sid)))

(defn- select-job [{:keys [conn]} job-sid]
  (some-> (ej/select-by-sid conn job-sid)
          (db->job)))

(def join-request? (partial global-sid? st/join-requests))

(defn- insert-join-request [conn jr]
  (let [user (ec/select-user conn (ec/by-cuid (:user-id jr)))
        org (ec/select-org conn (ec/by-cuid (:org-id jr)))
        e (-> jr
              (id->cuid)
              (select-keys [:cuid :status :request-msg :response-msg])
              (update :status name)
              (assoc :org-id (:id org)
                     :user-id (:id user)))]
    (ec/insert-join-request conn e)))

(defn- update-join-request [conn jr existing]
  (ec/update-join-request conn
                          (-> (select-keys jr [:status :request-msg :response-msg])
                              (update :status name)
                              (as-> x (merge existing x)))))

(defn- upsert-join-request [conn jr]
  (if-let [existing (ec/select-join-request conn (ec/by-cuid (:id jr)))]
    (update-join-request conn jr existing)
    (insert-join-request conn jr)))

(defn- select-join-request [conn cuid]
  (jr/select-join-request-as-entity conn cuid))

(defn- select-user-join-requests [{:keys [conn]} user-cuid]
  (letfn [(db->jr [r]
            (update r :status keyword))]
    (->> (jr/select-user-join-requests conn user-cuid)
         (map db->jr))))

(def email-registration? (partial global-sid? st/email-registrations))

(defn- db->email-registration [reg]
  (cuid->id reg))

(defn- select-email-registration [conn cuid]
  (some-> (ec/select-email-registration conn (ec/by-cuid cuid))
          (db->email-registration)))

(defn- select-email-registration-by-email [{:keys [conn]} email]
  (some-> (ec/select-email-registration conn [:= :email email])
          (db->email-registration)))

(defn- select-email-registrations [{:keys [conn]}]
  (->> (ec/select-email-registrations conn nil)
       (map db->email-registration)))

(defn- insert-email-registration [conn reg]
  ;; Updates not supported
  (ec/insert-email-registration conn (-> reg
                                         (assoc :cuid (:id reg))
                                         (dissoc :id))))

(defn- delete-email-registration [conn cuid]
  (ec/delete-email-registrations conn (ec/by-cuid cuid)))

(def credit-subscription? (partial global-sid? st/credit-subscriptions))

(defn- credit-sub->db [cs]
  (id->cuid cs))

(defn- db->credit-sub [cs]
  (mc/filter-vals some? cs))

(defn- insert-credit-subscription [conn cs]
  (let [org (ec/select-org conn (ec/by-cuid (:org-id cs)))]
    (ec/insert-credit-subscription conn (assoc (credit-sub->db cs)
                                               :org-id (:id org)))))

(defn- update-credit-subscription [conn cs existing]
  (ec/update-credit-subscription conn (merge existing
                                             (-> (credit-sub->db cs)
                                                 (dissoc :org-id)))))

(defn- upsert-credit-subscription [conn cs]
  (if-let [existing (ec/select-credit-subscription conn (ec/by-cuid (:id cs)))]
    (update-credit-subscription conn cs existing)
    (insert-credit-subscription conn cs)))

(defn- delete-credit-subscription [conn cuid]
  (ec/delete-credit-subscriptions conn (ec/by-cuid cuid)))

(defn- select-credit-subscription [conn cuid]
  (some->> (ecsub/select-credit-subs conn (ecsub/by-cuid cuid))
           (first)
           (db->credit-sub)))

(defn- select-credit-subs [conn f]
  (->> (ecsub/select-credit-subs conn f)
       (map db->credit-sub)))

(defn- select-org-credit-subs [{:keys [conn]} org-id]
  (select-credit-subs conn (ecsub/by-org org-id)))

(defn- select-active-credit-subs [{:keys [conn]} at]
  (select-credit-subs conn (ecsub/active-at at)))

(def org-credit? (partial global-sid? st/customer-credits))

(defn- org-credit->db [cred]
  (id->cuid cred))

(defn- db->org-credit [cred]
  (mc/filter-vals some? cred))

(defn- insert-org-credit [conn {:keys [subscription-id user-id] :as cred}]
  (let [org (ec/select-org conn (ec/by-cuid (:org-id cred)))
        cs   (when subscription-id
               (or (ec/select-credit-subscription conn (ec/by-cuid subscription-id))
                   (throw (ex-info "Subscription not found" cred))))
        user (when user-id
               (or (ec/select-user conn (ec/by-cuid user-id))
                   (throw (ex-info "User not found" cred))))]
    (ec/insert-org-credit conn (-> cred
                                   (org-credit->db)
                                   (assoc :org-id (:id org)
                                          :subscription-id (:id cs)
                                          :user-id (:id user))))))

(defn- update-org-credit [conn cred existing]
  (ec/update-org-credit conn (merge existing (select-keys cred [:amount :from-time]))))

(defn- upsert-org-credit [conn cred]
  (if-let [existing (ec/select-org-credit conn (ec/by-cuid (:id cred)))]
    (update-org-credit conn cred existing)
    (insert-org-credit conn cred)))

(defn- select-org-credit [conn id]
  (some->> (ecc/select-org-credits conn (ecc/by-cuid id))
           (first)
           (db->org-credit)))

(defn- select-org-credits-since [{:keys [conn]} org-id since]
  (->> (ecc/select-org-credits conn (ecc/by-org-since org-id since))
       (map db->org-credit)))

(defn- select-org-credits [{:keys [conn]} org-id]
  (->> (ecc/select-org-credits conn (ecc/by-org org-id))
       (map db->org-credit)))

(defn- select-avail-credits-amount [{:keys [conn]} org-id]
  ;; TODO Use the available-credits table for faster lookup
  (ecc/select-avail-credits-amount conn org-id))

(defn- select-avail-credits [{:keys [conn]} org-id]
  (->> (ecc/select-avail-credits conn org-id)
       (map db->org-credit)))

(def credit-consumption? (partial global-sid? st/credit-consumptions))

(defn- credit-cons->db [cc]
  (-> (id->cuid cc)
      (dissoc :org-id :repo-id)))

(defn- db->credit-cons [cc]
  (mc/filter-vals some? cc))

(def build-sid (juxt :org-id :repo-id :build-id))

(defn- insert-credit-consumption [conn cc]
  (let [build (apply eb/select-build-by-sid conn (build-sid cc))
        credit (ec/select-org-credit conn (ec/by-cuid (:credit-id cc)))]
    (when-not build
      (throw (ex-info "Build not found" cc)))
    (when-not credit
      (throw (ex-info "Org credit not found" cc)))
    (ec/insert-credit-consumption conn (assoc (credit-cons->db cc)
                                              :build-id (:id build)
                                              :credit-id (:id credit)))))

(defn- update-credit-consumption [conn cc existing]
  (ec/update-credit-consumption conn (merge existing
                                            (-> (credit-cons->db cc)
                                                (dissoc :build-id :credit-id)))))

(defn- upsert-credit-consumption [conn cc]
  ;; TODO Update available-credits table
  (if-let [existing (ec/select-credit-consumption conn (ec/by-cuid (:id cc)))]
    (update-credit-consumption conn cc existing)
    (insert-credit-consumption conn cc)))

(defn- select-credit-consumption [conn cuid]
  (some->> (eccon/select-credit-cons conn (eccon/by-cuid cuid))
           (first)
           (db->credit-cons)))

(defn- select-org-credit-cons [{:keys [conn]} org-id]
  (->> (eccon/select-credit-cons conn (eccon/by-org org-id))
       (map db->credit-cons)))

(defn- select-org-credit-cons-since [{:keys [conn]} org-id since]
  (->> (eccon/select-credit-cons conn (eccon/by-org-since org-id since))
       (map db->credit-cons)))

(def bb-webhook? (partial global-sid? st/bb-webhooks))

(defn- upsert-bb-webhook [conn bb-wh]
  (let [wh (-> (ec/select-webhooks conn (ec/by-cuid (:webhook-id bb-wh)))
               first)]
    ;; TODO Update?
    (ec/insert-bb-webhook conn (-> bb-wh
                                   (id->cuid)
                                   (assoc :webhook-id (:id wh))))))

(defn- select-bb-webhook [conn cuid]
  (some-> (ebbwh/select-bb-webhooks conn (ebbwh/by-cuid cuid))
          first
          (cuid->id)))

(defn- select-bb-webhook-for-webhook [{:keys [conn]} cuid]
  (some-> (ebbwh/select-bb-webhooks conn (ebbwh/by-wh-cuid cuid))
          (first)
          (cuid->id)))

(defn- select-bb-webhooks-by-filter [{:keys [conn]} f]
  (->> (ebbwh/select-bb-webhooks-with-repos conn (ebbwh/by-filter f))
       (map cuid->id)))

(def crypto? (partial global-sid? st/crypto))

(defn- insert-crypto [conn crypto org-id]
  (ec/insert-crypto conn (-> crypto
                             (assoc :org-id org-id))))

(defn- update-crypto [conn crypto existing]
  (ec/update-crypto conn (merge crypto (select-keys existing [:org-id]))))

(defn- upsert-crypto [conn crypto]
  (let [org (ec/select-org conn (ec/by-cuid (:org-id crypto)))
        existing (ec/select-crypto conn (ec/by-org (:id org)))]
    (if existing
      (update-crypto conn crypto existing)
      (insert-crypto conn crypto (:id org)))))

(defn- select-crypto [conn org-cuid]
  (ecu/crypto-by-org-cuid conn org-cuid))

(def sysadmin? (partial global-sid? st/sysadmin))

(defn- insert-sysadmin [conn sysadmin user-id]
  (ec/insert-sysadmin conn (-> sysadmin
                               (assoc :user-id user-id)))
  user-id)

(defn- update-sysadmin [conn sysadmin existing]
  (ec/update-sysadmin conn (merge sysadmin (select-keys existing [:user-id]))))

(defn- upsert-sysadmin [conn sysadmin]
  (let [user (ec/select-user conn (ec/by-cuid (:user-id sysadmin)))
        existing (ec/select-sysadmin conn (ec/by-user (:id user)))]
    (if existing
      (update-sysadmin conn sysadmin existing)
      (insert-sysadmin conn sysadmin (:id user)))))

(defn- select-sysadmin [conn user-cuid]
  (some-> (eu/select-sysadmin-by-user-cuid conn user-cuid)
          (assoc :user-id user-cuid)))

(def invoice? (partial global-sid? st/invoice))

(defn- db->invoice [inv]
  (-> inv
      (cuid->id)
      (assoc :org-id (:org-cuid inv))
      (dissoc :org-cuid)))

(defn- select-invoice [conn cuid]
  (some-> (ei/select-invoice-with-org conn cuid)
          db->invoice))

(defn- select-invoices-for-org [{:keys [conn]} org-cuid]
  (->> (ei/select-invoices-for-org conn org-cuid)
       (map db->invoice)))

(defn- insert-invoice [conn inv]
  (when-let [org (ec/select-org conn (ec/by-cuid (:org-id inv)))]
    (ec/insert-invoice conn (-> inv
                                (id->cuid)
                                (assoc :org-id (:id org))))))

(defn- update-invoice [conn inv existing]
  (ec/update-invoice conn (merge existing
                                 (-> inv
                                     (dissoc :id :org-id)))))

(defn- upsert-invoice [conn inv]
  (if-let [existing (ec/select-invoice conn (ec/by-cuid (:id inv)))]
    (update-invoice conn inv existing)
    (insert-invoice conn inv)))

(defn- runner-details-sid->build-sid [sid]
  (take-last (count st/build-sid-keys) sid))

(defn- runner-details->db [details]
  (select-keys details [:runner :details]))

(defn- insert-runner-details [conn details sid]
  (when-let [b (apply eb/select-build-by-sid conn sid)]
    (ec/insert-build-runner-detail conn (-> (runner-details->db details)
                                            (assoc :build-id (:id b))))))

(defn- update-runner-details [conn details existing]
  (ec/update-build-runner-detail conn (merge existing (runner-details->db details))))

(defn- upsert-runner-details [conn sid details]
  (if-let [match (eb/select-runner-details conn (eb/by-build-sid sid))]
    (update-runner-details conn details match)
    (insert-runner-details conn details sid)))

(defn- select-runner-details [conn sid]
  (some-> (eb/select-runner-details conn (eb/by-build-sid sid))
          (dissoc :build-id)))

(defn- insert-queued-task [conn task]
  (ec/insert-queued-task conn (id->cuid task)))

(defn- update-queued-task [conn task existing]
  (ec/update-queued-task conn (merge existing (id->cuid task))))

(defn- upsert-queued-task [conn cuid task]
  (if-let [match (ec/select-queued-task conn (ec/by-cuid cuid))]
    (update-queued-task conn task match)
    (insert-queued-task conn task)))

(defn- select-queued-tasks [{:keys [conn]}]
  (->> (ec/select-queued-tasks conn nil)
       (map cuid->id)))

(defn- delete-queued-task [conn cuid]
  (ec/delete-queued-tasks conn (ec/by-cuid cuid)))

(defn- sid-pred [t sid]
  (t sid))

(def runner-details? (partial global-sid? st/runner-details))

(def queued-task? (partial global-sid? st/queued-task))

(defrecord SqlStorage [conn vault]
  p/Storage
  (read-obj [_ sid]
    (condp sid-pred sid
      org?
      (select-org conn (global-sid->cuid sid))
      user?
      (select-user-by-type conn (drop 2 sid))
      build?
      (select-build conn (rest sid))
      webhook?
      (select-webhook conn (global-sid->cuid sid))
      ssh-key?
      (select-ssh-keys conn (second sid))
      params?
      (select-params conn (second sid))
      join-request?
      (select-join-request conn (global-sid->cuid sid))
      email-registration?
      (select-email-registration conn (global-sid->cuid sid))
      credit-subscription?
      (select-credit-subscription conn (last sid))
      credit-consumption?
      (select-credit-consumption conn (last sid))
      org-credit?
      (select-org-credit conn (global-sid->cuid sid))
      bb-webhook?
      (select-bb-webhook conn (last sid))
      crypto?
      (select-crypto conn (last sid))
      sysadmin?
      (select-sysadmin conn (last sid))
      invoice?
      (select-invoice conn (last sid))
      runner-details?
      (select-runner-details conn (runner-details-sid->build-sid sid))))
  
  (write-obj [_ sid obj]
    (when (condp sid-pred sid
            org?
            (upsert-org conn obj)
            user?
            (upsert-user conn obj)
            join-request?
            (upsert-join-request conn obj)
            build?
            (upsert-build conn obj)
            webhook?
            (upsert-webhook conn obj)
            ssh-key?
            (upsert-ssh-keys conn (last sid) obj)
            params?
            (upsert-params conn (last sid) obj)
            email-registration?
            (insert-email-registration conn obj)
            credit-subscription?
            (upsert-credit-subscription conn obj)
            credit-consumption?
            (upsert-credit-consumption conn obj)
            org-credit?
            (upsert-org-credit conn obj)
            bb-webhook?
            (upsert-bb-webhook conn obj)
            crypto?
            (upsert-crypto conn obj)
            sysadmin?
            (upsert-sysadmin conn obj)
            invoice?
            (upsert-invoice conn obj)
            runner-details?
            (upsert-runner-details conn (runner-details-sid->build-sid sid) obj)
            queued-task?
            (upsert-queued-task conn (last sid) obj)
            (log/warn "Unrecognized sid when writing:" sid))
      sid))

  (obj-exists? [_ sid]
    (condp sid-pred sid
      org?
      (org-exists? conn (global-sid->cuid sid))
      build?
      (build-exists? conn (rest sid))
      nil))

  (delete-obj [_ sid]
    (deleted?
     (condp sid-pred sid
       org?
       (delete-org conn (global-sid->cuid sid))
       email-registration?
       (delete-email-registration conn (global-sid->cuid sid))
       webhook?
       (delete-webhook conn (last sid))
       credit-subscription?
       (delete-credit-subscription conn (last sid))
       queued-task?
       (delete-queued-task conn (last sid))
       (log/warn "Deleting entity" sid "is not supported"))))

  (list-obj [_ sid]
    (condp sid-pred sid
      build-repo?
      (select-repo-build-ids conn (rest sid))
      (log/warn "Unable to list objects for sid" sid)))

  p/Transactable
  (transact [this f]
    (jdbc/transact
     (:ds conn)
     (fn [c]
       ;; Recreate storage object, with the transacted connection
       (f (map->SqlStorage {:conn (assoc conn :ds c)
                            :overrides (:overrides this)})))))

  ;; It would be cleaner to remove migrations from storage, and put
  ;; it in a separate component
  co/Lifecycle
  (start [this]
    (log/debug "Starting DB connection")
    (emig/run-migrations! (assoc conn :vault vault))
    this)

  (stop [this]
    (when-let [ds (:ds conn)]
      (log/debug "Closing DB connection")
      (.close ds))
    this))

(defn select-watched-github-repos [{:keys [conn]} github-id]
  (let [matches (ec/select-repos conn [:= :github-id github-id])
        ;; Select all org records for the repos
        orgs (when (not-empty matches)
               (->> matches
                    (map :org-id)
                    (distinct)
                    (vector :in :id)
                    (ec/select-orgs conn)
                    (group-by :id)
                    (mc/map-vals first)))
        add-org-cuid (fn [r e]
                       (assoc r :org-id (str (get-in orgs [(:org-id e) :cuid]))))
        convert (fn [e]
                  (db->repo e add-org-cuid))]
    (map convert matches)))

(defn watch-github-repo [{:keys [conn]} {:keys [org-id] :as repo}]
  (when-let [org (ec/select-org conn (ec/by-cuid org-id))]
    (let [r (ec/insert-repo conn (repo->db repo (:id org)))]
      (sid/->sid [org-id (:display-id r)]))))

(defn unwatch-github-repo [{:keys [conn]} [org-id repo-id]]
  ;; TODO Use a single query with join
  (some? (when-let [org (ec/select-org conn (ec/by-cuid org-id))]
           (when-let [repo (ec/select-repo conn [:and
                                                 [:= :org-id (:id org)]
                                                 [:= :display-id repo-id]])]
             (ec/update-repo conn (assoc repo :github-id nil))))))

(def overrides
  {:watched-github-repos
   {:find select-watched-github-repos
    :watch watch-github-repo
    :unwatch unwatch-github-repo}
   :customer
   {:search select-orgs
    :find-multiple select-orgs-by-id
    :list-credits-since select-org-credits-since
    :list-credits select-org-credits
    :get-available-credits select-avail-credits-amount
    :list-available-credits select-avail-credits
    :list-credit-subscriptions select-org-credit-subs
    :list-credit-consumptions select-org-credit-cons
    :list-credit-consumptions-since select-org-credit-cons-since
    :find-latest-builds select-latest-org-builds
    :find-latest-n-builds select-latest-n-org-builds}
   :repo
   {:list-display-ids select-repo-display-ids
    :find-next-build-idx select-next-build-idx
    :find-webhooks select-repo-webhooks
    :delete delete-repo}
   :user
   {:find select-user
    :customers select-user-orgs}
   :join-request
   {:list-user select-user-join-requests}
   :build
   {:list select-repo-builds
    :list-since select-org-builds-since
    :find-latest select-latest-build}
   :job
   {:save upsert-job
    :find select-job}
   :email-registration
   {:list select-email-registrations
    :find-by-email select-email-registration-by-email}
   :param
   {:save upsert-org-param
    :find select-org-param
    :delete delete-org-param}
   :credit
   {:list-active-subscriptions select-active-credit-subs}
   :bitbucket
   {:find-for-webhook select-bb-webhook-for-webhook
    :search-webhooks select-bb-webhooks-by-filter}
   :invoice
   {:list-for-customer select-invoices-for-org}
   :queued-task
   {:list select-queued-tasks}})

(defn make-storage [conn]
  (map->SqlStorage {:conn conn
                    :overrides overrides}))

(defmethod st/make-storage :sql [{conf :storage}]
  (log/debug "Using SQL storage with configuration:" (dissoc conf :password))
  (let [conn {:ds (conn/->pool HikariDataSource (-> conf
                                                    (dissoc :url :type)
                                                    (assoc :jdbcUrl (:url conf))))
              :sql-opts {:dialect :mysql :quoted-snake true}}]
    (make-storage conn)))
