(ns monkey.ci.gui.customer.events
  (:require [monkey.ci.gui.github :as github]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-event-fx
 :customer/load
 (fn [{:keys [db]} [_ id]]
   (log/debug "Loading customer:" id)
   {:db (-> db (db/set-loading)
            (db/set-alerts [{:type :info
                             :message "Retrieving customer information..."}]))
    :dispatch [:secure-request
               :get-customer
               {:customer-id id}
               [:customer/load--success]
               [:customer/load--failed id]]}))

(rf/reg-event-fx
 :customer/maybe-load
 (fn [{:keys [db]} [_ id]]
   (let [existing (db/customer db)
         id (or id (r/customer-id db))]
     (when-not (= (:id existing) id)
       {:dispatch [:customer/load id]}))))

(rf/reg-event-db
 :customer/load--success
 (fn [db [_ {cust :body}]]
   (log/debug "Customer details loaded:" (clj->js cust))
   (-> db
       (db/unset-loading)
       (db/set-customer cust)
       (db/reset-alerts))))

(rf/reg-event-db
 :customer/load--failed
 (fn [db [_ id err]]
   (-> db
       (db/set-alerts [{:type :danger
                        :message (str "Could not load details for customer " id ": "
                                      (u/error-msg err))}])
       (db/unset-loading))))

(rf/reg-event-fx
 :customer/load-github-repos
 (fn [{:keys [db]} _]
   {:db (-> db
            (db/set-github-repos nil)
            (db/set-repo-alerts [{:type :info
                                  :message "Fetching repositories from Github..."}]))
    :dispatch-n [[::load-user-repos]
                 [::load-orgs]]}))

(rf/reg-event-fx
 ::load-user-repos
 (fn [_ _]
   ;; Turns out that this url gives different results than the one in :repos-url
   {:dispatch [::load-repos (github/api-url "/user/repos")]}))

(rf/reg-event-fx
 ::load-repos
 (fn [{:keys [db]} [_ url]]
   {:http-xhrio (github/api-request
                 db
                 {:method :get
                  :uri url
                  :params {:type "all"
                           :per_page 50}
                  :on-success [:customer/load-github-repos--success]
                  :on-failure [:customer/load-github-repos--failed]})}))

(rf/reg-event-fx
 ::load-orgs
 (fn [{:keys [db]} _]
   (let [u (ldb/github-user db)]
     {:http-xhrio (github/api-request
                   db
                   {:method :get
                    :uri (:organizations-url u)
                    :on-success [::load-orgs--success]
                    :on-failure [::load-orgs--failed]})})))

(rf/reg-event-fx
 ::load-orgs--success
 (fn [{:keys [db]} [_ orgs]]
   {:dispatch-n (map (comp (partial conj [::load-repos])
                           :repos-url)
                     orgs)}))

(rf/reg-event-fx
 ::load-orgs--failed
 (u/req-error-handler-db
  (fn [db [_ err]]
    (db/set-repo-alerts db
                        [{:type :danger
                          :message (str "Unable to fetch user orgs from Github: " (u/error-msg err))}]))))

(rf/reg-event-db
 :customer/load-github-repos--success
 (fn [db [_ new-repos]]
   (log/debug "Got github repos:" new-repos)
   (let [orig (db/github-repos db)
         all (vec (concat orig new-repos))]
     (-> db
         ;; Add to existing repos since we're doing multiple calls
         (db/set-github-repos all)
         (db/set-repo-alerts [{:type :success
                               :message (str "Found " (count all) " repositories in Github.")}])))))

(rf/reg-event-fx
 :repo/watch
 (fn [{:keys [db]} [_ repo]]
   (log/debug "Watching repo:" repo)
   (let [params (r/path-params (r/current db))]
     {:dispatch [:secure-request
                 :watch-github-repo
                 {:repo {:name (:name repo)
                         :url (:clone-url repo)
                         :customer-id (:customer-id params)
                         :github-id (:id repo)}
                  :customer-id (:customer-id params)}
                 [:repo/watch--success]
                 [:repo/watch--failed]]})))

(rf/reg-event-db
 :repo/watch--success
 (fn [db [_ {:keys [body]}]]
   (db/update-customer db update :repos conj body)))

(rf/reg-event-db
 :repo/watch--failed
 (fn [db [_ err]]
   (db/set-repo-alerts db [{:type :danger
                            :message (str "Failed to watch repo: " (u/error-msg err))}])))

(rf/reg-event-fx
 :repo/unwatch
 (fn [{:keys [db]} [_ repo]]
   (log/debug "Unwatching:" (str repo))
   {:dispatch [:secure-request
               :unwatch-github-repo
               {:repo-id (:id repo)
                :customer-id (:customer-id repo)}
               [:repo/unwatch--success]
               [:repo/unwatch--failed]]}))

(rf/reg-event-db
 :repo/unwatch--success
 (fn [db [_ {:keys [body]}]]
   (db/replace-repo db body)))

(rf/reg-event-db
 :repo/unwatch--failed
 (fn [db [_ err]]
   (db/set-repo-alerts db [{:type :danger
                            :message (str "Failed to unwatch repo: " (u/error-msg err))}])))
