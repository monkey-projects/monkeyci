(ns monkey.ci.storage.sql
  "Storage implementation that uses an SQL database for persistence.  This namespace provides
   a layer on top of the entities namespace to perform the required queries whenever a 
   document is saved or loaded."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [medley.core :as mc]
            [monkey.ci
             [protocols :as p]
             [sid :as sid]
             [storage :as st]]
            [monkey.ci.entities
             [bb-webhook :as ebbwh]
             [build :as eb]
             [core :as ec]
             [credit-cons :as eccon]
             [credit-subs :as ecsub]
             [invoice :as ei]
             [job :as ej]
             [migrations :as emig]
             [org :as ecu]
             [org-credit :as ecc]
             [user :as eu]]
            [monkey.ci.storage.sql
             [build :as sb]
             [common :refer :all]
             [credit-cons :as scco]
             [credit-sub :as scs]
             [email-registration :as ser]
             [job :as sj]
             [join-request :as sjr]
             [mailing :as sma]
             [org :as so]
             [org-credit :as soc]
             [org-token :as sot]
             [param :as sp]
             [repo :as sr]
             [ssh-key :as ss]
             [user :as su]
             [user-token :as sut]
             [webhook :as sw]]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as conn])
  (:import (com.zaxxer.hikari HikariDataSource)))

(defn- global-sid? [type sid]
  (= [st/global (name type)] (take 2 sid)))

(def org? (partial global-sid? :orgs))
(def webhook? (partial global-sid? :webhooks))

(defn- global-sid->cuid [sid]
  (nth sid 2))

(defn- top-sid? [type sid]
  (and (= 2 (count sid))
       (= (name type) (first sid))))

(def ssh-key? (partial top-sid? :ssh-keys))

(def params? (partial top-sid? :build-params))

(defn user? [sid]
  (and (= 4 (count sid))
       (= [st/global "users"] (take 2 sid))))

(defn build? [sid]
  (and (= "builds" (first sid))
       (= 4 (count sid))))

(defn build-repo? [sid]
  (and (= "builds" (first sid))
       (= 3 (count sid))))

(def join-request? (partial global-sid? st/join-requests))

(def email-registration? (partial global-sid? st/email-registrations))

(def credit-subscription? (partial global-sid? st/credit-subscriptions))

(def org-credit? (partial global-sid? st/org-credits))

(def credit-consumption? (partial global-sid? st/credit-consumptions))

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

(defn- select-bb-webhook-for-webhook [st cuid]
  (some-> (ebbwh/select-bb-webhooks (get-conn st) (ebbwh/by-wh-cuid cuid))
          (first)
          (cuid->id)))

(defn- select-bb-webhooks-by-filter [st f]
  (->> (ebbwh/select-bb-webhooks-with-repos (get-conn st) (ebbwh/by-filter f))
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
    (log/debug "Upserting crypto" crypto)
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

(defn- select-invoices-for-org [st org-cuid]
  (->> (ei/select-invoices-for-org (get-conn st) org-cuid)
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

(defn- select-queued-tasks [st]
  (->> (ec/select-queued-tasks (get-conn st) nil)
       (map cuid->id)))

(defn- delete-queued-task [conn cuid]
  (ec/delete-queued-tasks conn (ec/by-cuid cuid)))

(defn- job-evt->db [evt]
  (-> evt
      (select-keys [:event :time :details :job-id])))

(defn- db->job-evt [evt]
  (-> evt
      (select-keys [:event :time :details])
      (assoc :job-id (:job-display-id evt)
             :build-id (:build-display-id evt)
             :repo-id (:repo-display-id evt)
             :org-id (:org-cuid evt))))

(defn- insert-job-event [conn sid evt]
  (when-let [job (->> sid
                      (drop 2)
                      (ej/select-by-sid conn))]
    (ec/insert-job-event conn (-> evt
                                  (assoc :job-id (:id job))
                                  (job-evt->db)))))

(defn- select-job-events [st job-sid]
  (->> (ej/select-events (get-conn st) job-sid)
       (map db->job-evt)))

(defn- sid-pred [t sid]
  (t sid))

(def runner-details? (partial global-sid? st/runner-details))

(def queued-task? (partial global-sid? st/queued-task))

(def job-event? (partial global-sid? st/job-event))

(def user-token? (partial global-sid? st/user-token))
(def org-token? (partial global-sid? st/org-token))
(def mailing? (partial global-sid? st/mailing))

(defrecord SqlStorage [pool]
  p/Storage
  (read-obj [this sid]
    (let [conn (get-conn this)]
      (condp sid-pred sid
        org?
        (so/select-org conn (global-sid->cuid sid))
        user?
        (su/select-user-by-type conn (drop 2 sid))
        build?
        (sb/select-build conn (rest sid))
        webhook?
        (sw/select-webhook conn (global-sid->cuid sid))
        ssh-key?
        (ss/select-ssh-keys conn (second sid))
        params?
        (sp/select-params conn (second sid))
        join-request?
        (sjr/select-join-request conn (global-sid->cuid sid))
        email-registration?
        (ser/select-email-registration conn (global-sid->cuid sid))
        credit-subscription?
        (scs/select-credit-subscription conn (last sid))
        credit-consumption?
        (scco/select-credit-consumption conn (last sid))
        org-credit?
        (soc/select-org-credit conn (global-sid->cuid sid))
        bb-webhook?
        (select-bb-webhook conn (last sid))
        crypto?
        (select-crypto conn (last sid))
        sysadmin?
        (select-sysadmin conn (last sid))
        invoice?
        (select-invoice conn (last sid))
        runner-details?
        (select-runner-details conn (runner-details-sid->build-sid sid))
        user-token?
        (sut/select-user-token conn (take-last 2 sid))
        org-token?
        (sot/select-org-token conn (take-last 2 sid))
        mailing?
        (sma/select-mailing conn (last sid)))))
  
  (write-obj [this sid obj]
    (let [conn (get-conn this)]
      (when (condp sid-pred sid
              org?
              (so/upsert-org conn obj)
              user?
              (su/upsert-user conn obj)
              join-request?
              (sjr/upsert-join-request conn obj)
              build?
              (sb/upsert-build conn obj)
              webhook?
              (sw/upsert-webhook conn obj)
              ssh-key?
              (ss/upsert-ssh-keys conn (last sid) obj)
              params?
              (sp/upsert-params conn (last sid) obj)
              email-registration?
              (ser/insert-email-registration conn obj)
              credit-subscription?
              (scs/upsert-credit-subscription conn obj)
              credit-consumption?
              (scco/upsert-credit-consumption conn obj)
              org-credit?
              (soc/upsert-org-credit conn obj)
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
              job-event?
              (insert-job-event conn sid obj)
              user-token?
              (sut/upsert-user-token conn obj)
              org-token?
              (sot/upsert-org-token conn obj)
              mailing?
              (sma/upsert-mailing conn obj)
              (log/warn "Unrecognized sid when writing:" sid))
        sid)))

  (obj-exists? [this sid]
    (let [conn (get-conn this)]
      (condp sid-pred sid
        org?
        (so/org-exists? conn (global-sid->cuid sid))
        build?
        (sb/build-exists? conn (rest sid))
        nil)))

  (delete-obj [this sid]
    (let [conn (get-conn this)]
      (deleted?
       (condp sid-pred sid
         org?
         (so/delete-org conn (global-sid->cuid sid))
         email-registration?
         (ser/delete-email-registration conn (global-sid->cuid sid))
         webhook?
         (sw/delete-webhook conn (last sid))
         credit-subscription?
         (scs/delete-credit-subscription conn (last sid))
         queued-task?
         (delete-queued-task conn (last sid))
         user-token?
         (sut/delete-user-token conn (last sid))
         org-token?
         (sot/delete-org-token conn (last sid))
         mailing?
         (sma/delete-mailing conn (last sid))
         (log/warn "Deleting entity" sid "is not supported")))))

  (list-obj [this sid]
    (let [conn (get-conn this)]
      (condp sid-pred sid
        build-repo?
        (sb/select-repo-build-ids conn (rest sid))
        (log/warn "Unable to list objects for sid" sid))))

  p/Transactable
  (transact [this f]
    (let [conn (get-conn this)]
      (jdbc/transact
       (:ds conn)
       (fn [c]
         ;; Recreate storage object, with the transacted connection
         (f (map->SqlStorage {:get-conn (constantly (assoc conn :ds c))
                              :overrides (:overrides this)})))))))

(defn select-watched-github-repos [st github-id]
  (let [conn (get-conn st)
        matches (ec/select-repos conn [:= :github-id github-id])
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
                  (sr/db->repo e add-org-cuid))]
    (map convert matches)))

(defn watch-github-repo [st {:keys [org-id] :as repo}]
  (let [conn (get-conn st)]
    (when-let [org (ec/select-org conn (ec/by-cuid org-id))]
      (let [r (ec/insert-repo conn (sr/repo->db repo (:id org)))]
        (sid/->sid [org-id (:display-id r)])))))

(defn unwatch-github-repo [st [org-id repo-id]]
  (let [conn (get-conn st)]
    ;; TODO Use a single query with join
    (some? (when-let [org (ec/select-org conn (ec/by-cuid org-id))]
             (when-let [repo (ec/select-repo conn [:and
                                                   [:= :org-id (:id org)]
                                                   [:= :display-id repo-id]])]
               (ec/update-repo conn (assoc repo :github-id nil)))))))

(def overrides
  {:watched-github-repos
   {:find select-watched-github-repos
    :watch watch-github-repo
    :unwatch unwatch-github-repo}
   :org
   {:init so/init-org
    :search so/select-orgs
    :find-multiple so/select-orgs-by-id
    :list-credits-since soc/select-org-credits-since
    :list-credits soc/select-org-credits
    :get-available-credits soc/select-avail-credits-amount
    :list-available-credits soc/select-avail-credits
    :list-credit-subscriptions scs/select-org-credit-subs
    :list-credit-consumptions scco/select-org-credit-cons
    :list-credit-consumptions-since scco/select-org-credit-cons-since
    :find-latest-builds sb/select-latest-org-builds
    :find-latest-n-builds sb/select-latest-n-org-builds
    :find-by-display-id so/select-org-by-display-id
    :find-id-by-display-id so/select-org-id-by-display-id
    :count so/count-orgs
    :list-tokens sot/select-org-tokens
    :find-token sot/select-org-token-by-token}
   :repo
   {:list-display-ids sr/select-repo-display-ids
    :find-next-build-idx sb/select-next-build-idx
    :find-webhooks sw/select-repo-webhooks
    :delete sr/delete-repo
    :count sr/count-repos}
   :user
   {:find su/select-user
    :orgs su/select-user-orgs
    :count su/count-users
    :delete su/delete-user
    :list-tokens sut/select-user-tokens
    :find-token sut/select-user-token-by-token}
   :join-request
   {:list-user sjr/select-user-join-requests}
   :build
   {:list sb/select-repo-builds
    :list-since sb/select-org-builds-since
    :find-latest sb/select-latest-build}
   :job
   {:save sj/upsert-job
    :find sj/select-job
    :list-events select-job-events}
   :email-registration
   {:list ser/select-email-registrations
    :find-by-email ser/select-email-registration-by-email
    :count ser/count-email-registrations}
   :param
   {:save sp/upsert-org-param
    :find sp/select-org-param
    :delete sp/delete-org-param}
   :credit
   {:list-active-subscriptions scs/select-active-credit-subs}
   :bitbucket
   {:find-for-webhook select-bb-webhook-for-webhook
    :search-webhooks select-bb-webhooks-by-filter}
   :invoice
   {:list-for-org select-invoices-for-org}
   :queued-task
   {:list select-queued-tasks}
   :mailing
   {:list sma/select-mailings
    :save-sent sma/upsert-sent-mailing
    :find-sent sma/select-sent-mailing
    :list-sent sma/select-sent-mailings}})

(defn make-storage [conn-fn]
  (map->SqlStorage {:get-conn conn-fn
                    :overrides overrides}))

(defn- pool->conn [pool]
  {:ds (pool)
   :sql-opts {:dialect :mysql :quoted-snake true}})

(defmethod st/make-storage :sql [_]
  (make-storage (comp pool->conn :pool)))

(defn pool-component [conf]
  (log/debug "Creating SQL connection pool with configuration:" (dissoc conf :password))
  (conn/component HikariDataSource
                  (-> conf
                      (dissoc :url :type)
                      (assoc :jdbcUrl (:url conf)))))

(defrecord DbMigrator [pool]
  co/Lifecycle
  (start [this]
    (emig/run-migrations! (merge (pool->conn pool) (select-keys this [:vault :crypto])))
    this)

  (stop [this]
    this))

(defn migrations-component []
  (->DbMigrator nil))
