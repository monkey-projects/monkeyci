(ns migration
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [config :refer [load-edn]]
            [storage :refer [make-storage]]
            [medley.core :as mc]
            [monkey.ci.storage :as s]))

(defn- migrate-dir [p sid st]
  (let [files (seq (.listFiles p))
        sid-name (fn [f]
                   (let [n (.getName f)
                         l (.lastIndexOf n ".")]
                     (subs n 0 l)))
        make-sid (fn [f]
                   (concat sid [(sid-name f)]))]
    (doseq [f files]
      (cond
        (.isFile f)
        (do
          (log/info "Migrating:" f)
          (s/write-obj st (make-sid f) (load-edn f)))
        (.isDirectory f)
        (migrate-dir f (concat sid [(.getName f)]) st)))))

(defn migrate-storage
  "Migrates all files from given directory to destiny storage."
  [dir st]
  (let [f (io/file dir)]
    (log/info "Migrating storage from" (.getCanonicalPath f))
    (migrate-dir f [] st)))

(defn- repo-list [cust]
  (->> cust
       :projects
       (mapcat (fn [[pid p]]
                 (->> p
                      :repos
                      (map (fn [[rid r]]
                             (assoc r :project-id pid))))))))

(defn- unique-repo-ids? [l]
  (->> l
       (group-by :id)
       (vals)
       (every? (comp (partial = 1) count))))

(defn- verify-unique-repo-ids! [l]
  (when-not (unique-repo-ids? l)
    (throw (ex-info "There are repository id collisions, please fix these first" l))))

(defn- add-project-label [r]
  (-> r
      (update :labels conj {:name "project" :value (:project-id r)})
      (dissoc :project-id)))

(defn- remove-build-projects
  "Looks up all builds for the repo and rewrites them to remove project from the sid.
   Returns a list of actions to perform."
  [st {:keys [customer-id project-id id]}]
  (let [old-repo-sid [customer-id project-id id]
        new-sid [customer-id id]
        builds (s/list-builds st old-repo-sid)]
    (log/info "Found" (count builds) "builds for repo" old-repo-sid)
    (mapcat
     (fn [bid]
       (let [old-bid (conj old-repo-sid bid)
             new-bid (conj new-sid bid)
             ;; Look up build info
             md  (-> (s/find-build-metadata st old-bid)
                     (dissoc :project-id))
             res (s/find-build-results st old-bid)]
         (log/info "Moving build" bid)
         ;; Prepare to store objects in new location
         [{:action :write
           :obj md
           :sid (s/build-metadata-sid new-bid)}
          {:action :write
           :obj res
           :sid (s/build-results-sid new-bid)}
          {:action :delete
           :sid (s/build-metadata-sid old-bid)}
          {:action :delete
           :sid (s/build-results-sid old-bid)}]))
     builds)))

(defn- remove-parameter-projects
  "Rewrites parameters so they are only attached to customer.  Project and repo
   info is replaced with label filters."
  [st cust repos]
  (let [cid (:id cust)
        cust-params {:parameters (s/find-legacy-params st [cid])
                     :label-filters []}
        project-params (->> cust
                            :projects
                            (keys)
                            (map (fn [pid]
                                   [pid {:parameters (s/find-legacy-params st [cid pid])
                                         :label-filters [[{:label "project" :value pid}]]}]))
                            (filter (comp not-empty :parameters second))
                            (into {}))
        repo-params (->> repos
                         (map (fn [{:keys [project-id id] :as r}]
                                [r {:parameters (s/find-legacy-params st [cid project-id id])
                                    :label-filters [[{:label "project" :value project-id}
                                                     {:label "repo" :value id}]]}]))
                         (filter (comp not-empty :parameters second))
                         (into {}))
        all-params (-> (concat (when (not-empty (:parameters cust-params))
                                 [cust-params])
                               (vals project-params)
                               (vals repo-params))
                       (vec))]
    (log/info "Parameters to migrate:")
    (log/info "  -" (count (:parameters cust-params)) "customer params")
    (log/info "  -" (reduce + 0 (map (comp count :parameters) (vals project-params))) "project params")
    (log/info "  -" (reduce + 0 (map (comp count :parameters) (vals repo-params))) "repository params")
    (log/info "Total parameter group count:" (count all-params))
    (concat (when (not-empty all-params)
              [{:action :write
                :sid (s/params-sid cid)
                :obj all-params}])
            (when (not-empty (:parameters cust-params))
              [{:action :delete
                :sid (s/legacy-params-sid [cid])}])
            (map (fn [[pid]]
                   {:action :delete
                    :sid (s/legacy-params-sid [cid pid])})
                 project-params)
            (map (fn [[{:keys [project-id id]}]]
                   {:action :delete
                    :sid (s/legacy-params-sid [cid project-id id])})
                 repo-params))))

(defn remove-project-actions
  "Removes the project from the given customer.  This rewrites the customer,
   builds and parameters to link the repos directly to the customer.  The
   project is added to the parameters as a label filter, and to the repos
   as a label."
  [cust-id]
  (let [st (make-storage)
        cust (s/find-customer st cust-id)
        repos (repo-list cust)]
    (log/info "Migrating customer" (:name cust) "to remove project...")
    (verify-unique-repo-ids! repos)
    (log/info "Updating customer info...")
    (let [upd-repos (->> (repo-list cust)
                         (map add-project-label)
                         (group-by :id)
                         (mc/map-vals first))
          upd {:action :write
               :sid (s/customer-sid cust-id)
               :obj (-> cust
                        (dissoc :projects)
                        (assoc :repos upd-repos))}
          actions (concat [upd]
                          (mapcat (partial remove-build-projects st) repos)
                          (remove-parameter-projects st cust repos))]
      (log/info "Actions to execute:" (count actions))
      actions)))

(defn execute-actions! [actions]
  (let [st (make-storage)]
    (log/info "Executing" (count actions) "migration actions")
    (doseq [a actions]
      (log/debug "Executing action:" a)
      (case (:action a)
        :write  (s/write-obj st (:sid a) (:obj a))
        :delete (s/delete-obj st (:sid a))))
    (log/info "Actions executed")))

(defn remove-project! [cust-id]
  (-> (remove-project-actions cust-id)
      (execute-actions!)))
