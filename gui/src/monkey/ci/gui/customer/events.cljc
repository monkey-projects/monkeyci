(ns monkey.ci.gui.customer.events
  (:require [medley.core :as mc]
            [monkey.ci.gui.apis.github :as github]
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
   (let [cust-id (r/customer-id db)]
     {:dispatch [:secure-request
                 :watch-github-repo
                 {:repo {:name (:name repo)
                         :url (:clone-url repo)
                         :customer-id cust-id
                         :github-id (:id repo)}
                  :customer-id cust-id}
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
   {:dispatch [:secure-request
               :unwatch-github-repo
               {:repo-id (get-in repo [:monkeyci/repo :id])
                :customer-id (r/customer-id db)}
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

(rf/reg-event-fx
 :customer/create
 (fn [{:keys [db]} [_ cust]]
   {:dispatch [:secure-request
               :create-customer
               {:customer (mc/map-vals first cust)}
               [:customer/create--success]
               [:customer/create--failed]]
    :db (-> db
            (db/mark-customer-creating)
            (db/reset-create-alerts))}))

(rf/reg-event-fx
 :customer/create--success
 (fn [{:keys [db]} [_ {:keys [body]}]]
   {:db (-> db
            (db/unmark-customer-creating)
            (db/set-customer body)
            (db/set-alerts [{:type :success
                             :message [:span "Customer " [:b (:name body)] " has been created."]}]))
    ;; Redirect to customer page
    :dispatch [:route/goto :page/customer {:customer-id (:id body)}]}))

(rf/reg-event-db
 :customer/create--failed
 (fn [db [_ err]]
   (db/set-create-alerts db [{:type :danger
                              :message (str "Failed to create customer: " (u/error-msg err))}])))

(rf/reg-event-fx
 :customer/load-latest-builds
 (fn [{:keys [db]} [_ cust-id]]
   {:dispatch [:secure-request
               :get-latest-builds
               {:customer-id cust-id}
               [:customer/load-latest-builds--success]
               [:customer/load-latest-builds--failed]]
    :db (-> db
            (db/set-loading db/latest-builds)
            (db/reset-alerts db/latest-builds))}))

(rf/reg-event-db
 :customer/load-latest-builds--success
 (fn [db [_ {builds :body}]]
   (-> db
       (db/set-latest-builds builds)
       (db/unset-loading db/latest-builds))))

(rf/reg-event-db
 :customer/load-latest-builds--failed
 (fn [db [_ err]]
   (-> db
       (db/set-alerts db/latest-builds
                      [{:type :danger
                        :message (str "Failed to load latest builds: " (u/error-msg err))}])
       (db/unset-loading db/latest-builds))))
