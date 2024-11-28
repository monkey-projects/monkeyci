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
             [customer :as ecu]
             [customer-credit :as ecc]
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
  [r cust-id]
  (-> r
      (select-keys [:name :url :main-branch :github-id])
      (dissoc :id)
      (assoc :display-id (:id r)
             :customer-id cust-id)))

(defn- db->repo
  "Converts the repo entity (a db record) into a repository.  If `f` is provided,
   it is invoked to allow some processing on the resulting object."
  [re & [f]]
  (cond->
      (-> re
          (dissoc :cuid :customer-id :display-id)
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
    (insert-repo-labels conn (:labels repo) re)))

(defn- update-repo [conn re repo existing]
  (when (not= re existing)
    (let [re (merge existing re)]
      (ec/update-repo conn re)
      (sync-repo-labels conn (:labels repo) re))))

(defn- select-repo-id-by-sid [conn [cust-id repo-id]]
  (er/repo-for-build-sid conn cust-id repo-id))

(defn- upsert-repo [conn repo cust-id]
  (spec/valid? :entity/repo repo)
  (let [re (repo->db repo cust-id)]
    (spec/valid? :db/repo re)
    (if-let [existing (ec/select-repo conn [:and
                                            (ec/by-customer cust-id)
                                            (ec/by-display-id (:id repo))])]
      (update-repo conn re repo existing)
      (insert-repo conn re repo))))

(defn- upsert-repos [conn {:keys [repos]} cust-id]
  (doseq [[_ r] repos]
    (upsert-repo conn r cust-id)))

(defn- delete-repo [{:keys [conn]} sid]
  (when-let [repo-id (select-repo-id-by-sid conn sid)]
    ;; Other records are deleted by cascading
    (pos? (ec/delete-repos conn (ec/by-id repo-id)))))

(defn- select-repo-display-ids [{:keys [conn]} cust-id]
  (er/repo-display-ids conn cust-id))

(defn- cust->db [cust]
  (-> cust
      (id->cuid)
      (select-keys [:cuid :name])))

(defn- db->cust [c]
  (letfn [(entities->repos [repos]
            (reduce-kv (fn [r _ v]
                         (assoc r (:display-id v) (db->repo v)))
                       {}
                       repos))]
    (-> c
        (cuid->id)
        (mc/update-existing :repos entities->repos))))

(defn- insert-customer [conn cust]
  (let [cust-id (:id (ec/insert-customer conn (cust->db cust)))]
    (upsert-repos conn cust cust-id)
    cust))

(defn- update-customer [conn cust existing]
  (let [ce (cust->db cust)]
    (spec/valid? :db/customer ce)
    (when (not= ce existing)
      (ec/update-customer conn (merge existing ce)))
    (upsert-repos conn cust (:id existing))
    cust))

(defn- upsert-customer [conn cust]
  (spec/valid? :entity/customer cust)
  (if-let [existing (ec/select-customer conn (ec/by-cuid (:id cust)))]
    (update-customer conn cust existing)
    (insert-customer conn cust)))

(defn- select-customer [conn cuid]
  (when cuid
    (some-> (ecu/customer-with-repos conn (ec/by-cuid cuid))
            (db->cust))))

(defn- customer-exists? [conn cuid]
  (some? (ec/select-customer conn (ec/by-cuid cuid))))

(defn- delete-customer [conn cuid]
  (when cuid
    (ec/delete-customers conn (ec/by-cuid cuid))))

(defn- select-customers
  "Finds customers by filter"
  [{:keys [conn]} {:keys [id name]}]
  (let [query (cond
                id (ec/by-cuid id)
                ;; By default, this will use case insensitive search (depends on collation)
                name [:like :name (str "%" name "%")])]
    (->> (ec/select-customers conn query)
         (map db->cust))))

(defn- global-sid? [type sid]
  (= [st/global (name type)] (take 2 sid)))

(def customer? (partial global-sid? :customers))
(def webhook? (partial global-sid? :webhooks))

(defn- global-sid->cuid [sid]
  (nth sid 2))

(defn- insert-webhook [conn wh]
  (if-let [repo-id (select-repo-id-by-sid conn [(:customer-id wh) (:repo-id wh)])]
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

(defn- select-repo-webhooks [{:keys [conn]} [cust-id repo-id]]
  (ewh/select-webhooks-as-entity conn (ewh/by-repo cust-id repo-id)))

(defn- delete-webhook [conn cuid]
  (ec/delete-webhooks conn (ec/by-cuid cuid)))

(defn- top-sid? [type sid]
  (and (= 2 (count sid))
       (= (name type) (first sid))))

(def ssh-key? (partial top-sid? :ssh-keys))

(defn- ssh-key->db [k]
  (-> k
      (id->cuid)
      (dissoc :customer-id)))

(defn- insert-ssh-key [conn ssh-key cust-id]
  (log/debug "Inserting ssh key:" ssh-key)
  (ec/insert-ssh-key conn (-> ssh-key
                              (ssh-key->db)
                              (assoc :customer-id cust-id))))

(defn- update-ssh-key [conn ssh-key existing]
  (log/debug "Updating ssh key:" ssh-key)
  (ec/update-ssh-key conn (merge existing (ssh-key->db ssh-key))))

(defn- upsert-ssh-key [conn cust-id ssh-key]
  (spec/valid? :entity/ssh-key ssh-key)
  (if-let [existing (ec/select-ssh-key conn (ec/by-cuid (:id ssh-key)))]
    (update-ssh-key conn ssh-key existing)
    (insert-ssh-key conn ssh-key cust-id)))

(defn- upsert-ssh-keys [conn cust-cuid ssh-keys]
  (when (not-empty ssh-keys)
    (if-let [{cust-id :id} (ec/select-customer conn (ec/by-cuid cust-cuid))]
      (doseq [k ssh-keys]
        (upsert-ssh-key conn cust-id k))
      (throw (ex-info "Customer not found when upserting ssh keys" {:customer-id cust-cuid})))
    ssh-keys))

(defn- select-ssh-keys [conn customer-id]
  (essh/select-ssh-keys-as-entity conn customer-id))

(def params? (partial top-sid? :build-params))

(defn- insert-param-values [conn values param-id]
  (when-not (empty? values)
    (->> values
         (map (fn [v]
                (-> (select-keys v [:name :value])
                    (assoc :params-id param-id))))
         (ec/insert-customer-param-values conn))))

(defn- update-param-values [conn values]
  (doseq [pv values]
    (ec/update-customer-param-value conn pv)))

(defn- delete-param-values [conn values]
  (when-not (empty? values)
    (ec/delete-customer-param-values conn [:in :id (map :id values)])))

(defn- param->db [param cust-id]
  (-> param
      (id->cuid)
      (select-keys [:cuid :description :label-filters])
      (assoc :customer-id cust-id)))

(defn- insert-param [conn param cust-id]
  (let [{:keys [id]} (ec/insert-customer-param conn (param->db param cust-id))]
    (insert-param-values conn (:parameters param) id)))

(defn- update-param [conn param cust-id existing]
  (ec/update-customer-param conn (merge existing (param->db param cust-id)))
  (let [ex-vals (ec/select-customer-param-values conn (ec/by-params (:id existing)))
        r (lbl/reconcile-labels ex-vals (:parameters param))]
    (log/debug "Reconciled param values:" r)
    (insert-param-values conn (:insert r) (:id existing))
    (update-param-values conn (:update r))
    (delete-param-values conn (:delete r))))

(defn- upsert-param [conn param cust-id]
  (spec/valid? :entity/customer-params param)
  (if-let [existing (ec/select-customer-param conn (ec/by-cuid (:id param)))]
    (update-param conn param cust-id existing)
    (insert-param conn param cust-id)))

(defn- upsert-params [conn cust-cuid params]
  (when-not (empty? params)
    (let [{cust-id :id} (ec/select-customer conn (ec/by-cuid cust-cuid))]
      (doseq [p params]
        (upsert-param conn p cust-id))
      params)))

(defn- select-params [conn customer-id]
  ;; Select customer params and values for customer cuid
  (eparam/select-customer-params-with-values conn customer-id))

(defn- upsert-customer-param [{:keys [conn]} {:keys [customer-id] :as param}]
  (when-let [{db-id :id} (ec/select-customer conn (ec/by-cuid customer-id))]
    (upsert-param conn param db-id)
    (st/params-sid customer-id (:id param))))

(defn- select-customer-param [{:keys [conn]} [_ _ param-id]]
  (eparam/select-param-with-values conn param-id))

(defn- delete-customer-param [{:keys [conn]} [_ _ param-id]]
  (pos? (ec/delete-customer-params conn (ec/by-cuid param-id))))

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
        ids (ecu/customer-ids-by-cuids conn (:customers user))]
    (ec/insert-user-customers conn id ids)
    ins))

(defn- update-user [conn user {user-id :id :as existing}]
  (when (ec/update-user conn (merge existing (user->db user)))
    ;; Update user/customer links
    (let [existing-cust (set (eu/select-user-customer-cuids conn user-id))
          new-cust (set (:customers user))
          to-add (cset/difference new-cust existing-cust)
          to-remove (cset/difference existing-cust new-cust)]
      (ec/insert-user-customers conn user-id (ecu/customer-ids-by-cuids conn to-add))
      (when-not (empty? to-remove)
        (ec/delete-user-customers conn [:in :customer-id (ecu/customer-ids-by-cuids conn to-remove)]))
      user)))

(defn- upsert-user [conn user]
  (if-let [existing (ec/select-user conn (ec/by-cuid (:id user)))]
    (update-user conn user existing)
    (insert-user conn user)))

(defn- select-user-by-filter [conn f]
  (when-let [r (ec/select-user conn f)]
    (let [cust (eu/select-user-customer-cuids conn (:id r))]
      (cond-> (db->user r)
        true (drop-nil)
        (not-empty cust) (assoc :customers cust)))))

(defn- select-user-by-type [conn [type type-id]]
  (select-user-by-filter conn [:and
                               [:= :type type]
                               [:= :type-id type-id]]))

(defn- select-user [{:keys [conn]} id]
  (select-user-by-filter conn (ec/by-cuid id)))

(defn- select-user-customers [{:keys [conn]} id]
  (->> (eu/select-user-customers conn id)
       (map db->cust)))

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
      (mc/assoc-some :customer-id (:customer-cuid build)
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

(defn- insert-jobs [conn jobs build-id]
  (doseq [j jobs]
    (ec/insert-job conn (assoc (job->db j) :build-id build-id))))

(defn- update-jobs [conn jobs]
  (doseq [[upd ex] jobs]
    (let [upd (merge ex (job->db upd))]
      (ec/update-job conn upd))))

(defn- insert-build [conn build]
  (when-let [repo-id (er/repo-for-build-sid conn (:customer-id build) (:repo-id build))]
    (let [{:keys [id] :as ins} (ec/insert-build conn (-> (build->db build)
                                                         (assoc :repo-id repo-id)))]
      (insert-jobs conn (-> build :script :jobs vals) id)
      ins)))

(defn- update-build [conn build existing]
  ;; TODO Lock records before updating
  (ec/update-build conn (merge existing (build->db build)))
  (let [jobs (get-in build [:script :jobs])
        ex-jobs (ec/select-jobs conn (ec/by-build (:id existing)))
        new-ids (set (keys jobs))
        existing-ids (set (map :display-id ex-jobs))
        to-delete (cset/difference existing-ids new-ids)
        to-insert (apply dissoc jobs existing-ids)
        to-update (reduce (fn [r ej]
                            (let [n (get jobs (:display-id ej))]
                              (cond-> r
                                ;; TODO Only update modified jobs
                                n (conj [n ej]))))
                          []
                          ex-jobs)]
    (when-not (empty? to-delete)
      ;; Delete all removed jobs (although this is a situation that probably never happens)
      (ec/delete-jobs conn [:and
                            [:= :build-id (:id existing)]
                            [:in :display-id to-delete]]))
    (when-not (empty? to-update)
      (update-jobs conn to-update))
    (when-not (empty? to-insert)
      ;; Insert new jobs
      (insert-jobs conn (vals to-insert) (:id existing)))
    build))

(defn- upsert-build [conn build]
  ;; Fetch build by customer cuild and repo and build display ids
  (if-let [existing (eb/select-build-by-sid conn (:customer-id build) (:repo-id build) (:build-id build))]
    (update-build conn build existing)
    (insert-build conn build)))

(defn- select-jobs [conn build-id]
  (->> (ec/select-jobs conn (ec/by-build build-id))
       (map db->job)
       (map (fn [j] [(:id j) j]))
       (into {})))

(defn- hydrate-build [conn [cust-id repo-id] build]
  (let [jobs (select-jobs conn (:id build))]
    (cond-> (-> (db->build build)
                (assoc :customer-id cust-id
                       :repo-id repo-id)
                (update :script drop-nil)
                (drop-nil))
      (not-empty jobs) (assoc-in [:script :jobs] jobs))))

(defn- update-build-atomically
  "Updates build atomically by locking build and job records."
  [{:keys [conn]} sid f & args]
  (when-let [b (apply eb/select-build-by-sid-for-update conn sid)]
    ;; TODO Lock jobs as well?
    (let [h (hydrate-build conn sid b)]
      (when (update-build conn (apply f h args) b)
        sid))))

(defn- select-build [conn [cust-id repo-id :as sid]]
  (when-let [build (apply eb/select-build-by-sid conn sid)]
    (hydrate-build conn sid build)))

(defn- select-repo-builds
  "Retrieves all builds and their details for given repository"
  [{:keys [conn]} [cust-id repo-id]]
  (letfn [(add-ids [b]
            (assoc b
                   :customer-id cust-id
                   :repo-id repo-id))]
    ;; Fetch all build details, don't include jobs since we don't need them at this point
    ;; and they can become a very large dataset.
    (->> (eb/select-builds-for-repo conn cust-id repo-id)
         (map db->build)
         (map add-ids))))

(defn build-exists? [conn sid]
  (some? (apply eb/select-build-by-sid conn sid)))

(defn- select-repo-build-ids [conn sid]
  (apply eb/select-build-ids-for-repo conn sid))

(defn- select-customer-builds-since [{:keys [conn]} cust-id ts]
  (->> (eb/select-builds-for-customer-since conn cust-id ts)
       (map db->build)))

(defn- select-latest-build [{:keys [conn]} [cust-id repo-id]]
  (some-> (eb/select-latest-build conn cust-id repo-id)
          (db->build)))

(defn- select-max-build-idx [{:keys [conn]} [cust-id repo-id]]
  ;; TODO Use repo-indices table instead
  (eb/select-max-idx conn cust-id repo-id))

(def join-request? (partial global-sid? st/join-requests))

(defn- insert-join-request [conn jr]
  (let [user (ec/select-user conn (ec/by-cuid (:user-id jr)))
        cust (ec/select-customer conn (ec/by-cuid (:customer-id jr)))
        e (-> jr
              (id->cuid)
              (select-keys [:cuid :status :request-msg :response-msg])
              (update :status name)
              (assoc :customer-id (:id cust)
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
  (let [cust (ec/select-customer conn (ec/by-cuid (:customer-id cs)))]
    (ec/insert-credit-subscription conn (assoc (credit-sub->db cs)
                                               :customer-id (:id cust)))))

(defn- update-credit-subscription [conn cs existing]
  (ec/update-credit-subscription conn (merge existing
                                             (-> (credit-sub->db cs)
                                                 (dissoc :customer-id)))))

(defn- upsert-credit-subscription [conn cs]
  (if-let [existing (ec/select-credit-subscription conn (ec/by-cuid (:id cs)))]
    (update-credit-subscription conn cs existing)
    (insert-credit-subscription conn cs)))

(defn- select-credit-subscription [conn cuid]
  (some->> (ecsub/select-credit-subs conn (ecsub/by-cuid cuid))
           (first)
           (db->credit-sub)))

(defn- select-credit-subs [conn f]
  (->> (ecsub/select-credit-subs conn f)
       (map db->credit-sub)))

(defn- select-customer-credit-subs [{:keys [conn]} cust-id]
  (select-credit-subs conn (ecsub/by-cust cust-id)))

(defn- select-active-credit-subs [{:keys [conn]} at]
  (select-credit-subs conn (ecsub/active-at at)))

(def customer-credit? (partial global-sid? st/customer-credits))

(defn- customer-credit->db [cred]
  (id->cuid cred))

(defn- db->customer-credit [cred]
  (mc/filter-vals some? cred))

(defn- insert-customer-credit [conn {:keys [subscription-id user-id] :as cred}]
  (let [cust (ec/select-customer conn (ec/by-cuid (:customer-id cred)))
        cs   (when subscription-id
               (or (ec/select-credit-subscription conn (ec/by-cuid subscription-id))
                   (throw (ex-info "Subscription not found" cred))))
        user (when user-id
               (or (ec/select-user conn (ec/by-cuid user-id))
                   (throw (ex-info "User not found" cred))))]
    (ec/insert-customer-credit conn (-> cred
                                        (customer-credit->db)
                                        (assoc :customer-id (:id cust)
                                               :subscription-id (:id cs)
                                               :user-id (:id user))))))

(defn- update-customer-credit [conn cred existing]
  (ec/update-customer-credit conn (merge existing (select-keys cred [:amount :from-time]))))

(defn- upsert-customer-credit [conn cred]
  (if-let [existing (ec/select-customer-credit conn (ec/by-cuid (:id cred)))]
    (update-customer-credit conn cred existing)
    (insert-customer-credit conn cred)))

(defn- select-customer-credit [conn id]
  (some->> (ecc/select-customer-credits conn (ecc/by-cuid id))
           (first)
           (db->customer-credit)))

(defn- select-customer-credits-since [{:keys [conn]} cust-id since]
  (->> (ecc/select-customer-credits conn (ecc/by-cust-since cust-id since))
       (map db->customer-credit)))

(defn- select-avail-credits-amount [{:keys [conn]} cust-id]
  ;; TODO Use the available-credits table for faster lookup
  (ecc/select-avail-credits-amount conn cust-id))

(defn- select-avail-credits [{:keys [conn]} cust-id]
  (->> (ecc/select-avail-credits conn cust-id)
       (map db->customer-credit)))

(def credit-consumption? (partial global-sid? st/credit-consumptions))

(defn- credit-cons->db [cc]
  (-> (id->cuid cc)
      (dissoc :customer-id :repo-id)))

(defn- db->credit-cons [cc]
  (mc/filter-vals some? cc))

(def build-sid (juxt :customer-id :repo-id :build-id))

(defn- insert-credit-consumption [conn cc]
  (let [build (apply eb/select-build-by-sid conn (build-sid cc))
        credit (ec/select-customer-credit conn (ec/by-cuid (:credit-id cc)))]
    (when-not build
      (throw (ex-info "Build not found" cc)))
    (when-not credit
      (throw (ex-info "Customer credit not found" cc)))
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

(defn- select-customer-credit-cons [{:keys [conn]} cust-id]
  (->> (eccon/select-credit-cons conn (eccon/by-cust cust-id))
       (map db->credit-cons)))

(defn- select-customer-credit-cons-since [{:keys [conn]} cust-id since]
  (->> (eccon/select-credit-cons conn (eccon/by-cust-since cust-id since))
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

(defn- sid-pred [t sid]
  (t sid))

(defrecord SqlStorage [conn]
  p/Storage
  (read-obj [_ sid]
    (condp sid-pred sid
      customer?
      (select-customer conn (global-sid->cuid sid))
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
      customer-credit?
      (select-customer-credit conn (global-sid->cuid sid))
      bb-webhook?
      (select-bb-webhook conn (last sid))))
  
  (write-obj [_ sid obj]
    (when (condp sid-pred sid
            customer?
            (upsert-customer conn obj)
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
            customer-credit?
            (upsert-customer-credit conn obj)
            bb-webhook?
            (upsert-bb-webhook conn obj)
            (log/warn "Unrecognized sid when writing:" sid))
      sid))

  (obj-exists? [_ sid]
    (condp sid-pred sid
      customer?
      (customer-exists? conn (global-sid->cuid sid))
      build?
      (build-exists? conn (rest sid))
      nil))

  (delete-obj [_ sid]
    (deleted?
     ;; TODO Allow deleting other entities
     (condp sid-pred sid
       customer?
       (delete-customer conn (global-sid->cuid sid))
       email-registration?
       (delete-email-registration conn (global-sid->cuid sid))
       webhook?
       (delete-webhook conn (last sid))
       (log/warn "Deleting entity" sid "is not supported"))))

  (list-obj [_ sid]
    (condp sid-pred sid
      build-repo?
      (select-repo-build-ids conn (rest sid))
      (log/warn "Unable to list objects for sid" sid)))

  p/Transactable
  (transact [_ f]
    (jdbc/transact
     (:ds conn)
     (fn [c]
       ;; Recreate storage object, with the transacted connection
       (f (->SqlStorage (assoc conn :ds c))))))

  co/Lifecycle
  (start [this]
    (log/debug "Starting DB connection")
    (emig/run-migrations! conn)
    this)

  (stop [this]
    (when-let [ds (:ds conn)]
      (log/debug "Closing DB connection")
      (.close ds))
    this))

(defn select-watched-github-repos [{:keys [conn]} github-id]
  (let [matches (ec/select-repos conn [:= :github-id github-id])
        ;; Select all customer records for the repos
        customers (when (not-empty matches)
                    (->> matches
                         (map :customer-id)
                         (distinct)
                         (vector :in :id)
                         (ec/select-customers conn)
                         (group-by :id)
                         (mc/map-vals first)))
        add-cust-cuid (fn [r e]
                        (assoc r :customer-id (str (get-in customers [(:customer-id e) :cuid]))))
        convert (fn [e]
                  (db->repo e add-cust-cuid))]
    (map convert matches)))

(defn watch-github-repo [{:keys [conn]} {:keys [customer-id] :as repo}]
  (when-let [cust (ec/select-customer conn (ec/by-cuid customer-id))]
    (let [r (ec/insert-repo conn (repo->db repo (:id cust)))]
      (sid/->sid [customer-id (:display-id r)]))))

(defn unwatch-github-repo [{:keys [conn]} [customer-id repo-id]]
  ;; TODO Use a single query with join
  (some? (when-let [cust (ec/select-customer conn (ec/by-cuid customer-id))]
           (when-let [repo (ec/select-repo conn [:and
                                                 [:= :customer-id (:id cust)]
                                                 [:= :display-id repo-id]])]
             (ec/update-repo conn (assoc repo :github-id nil))))))

(def overrides
  {:watched-github-repos
   {:find select-watched-github-repos
    :watch watch-github-repo
    :unwatch unwatch-github-repo}
   :customer
   {:search select-customers
    :list-credits-since select-customer-credits-since
    :get-available-credits select-avail-credits-amount
    :list-available-credits select-avail-credits
    :list-credit-subscriptions select-customer-credit-subs
    :list-credit-consumptions select-customer-credit-cons
    :list-credit-consumptions-since select-customer-credit-cons-since}
   :repo
   {:list-display-ids select-repo-display-ids
    :find-next-build-idx (comp (fnil inc 0) select-max-build-idx)
    :find-webhooks select-repo-webhooks
    :delete delete-repo}
   :user
   {:find select-user
    :customers select-user-customers}
   :join-request
   {:list-user select-user-join-requests}
   :build
   {:update update-build-atomically
    :list select-repo-builds
    :list-since select-customer-builds-since
    :find-latest select-latest-build}
   :email-registration
   {:list select-email-registrations
    :find-by-email select-email-registration-by-email}
   :param
   {:save upsert-customer-param
    :find select-customer-param
    :delete delete-customer-param}
   :credit
   {:list-active-subscriptions select-active-credit-subs}
   :bitbucket
   {:find-for-webhook select-bb-webhook-for-webhook
    :search-webhooks select-bb-webhooks-by-filter}})

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
