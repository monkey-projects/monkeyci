(ns monkey.ci.gui.customer.events
  (:require [monkey.ci.gui.github :as github]
            [monkey.ci.gui.logging :as log]
            [monkey.ci.gui.martian]
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.login.db :as ldb]
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
 (fn [db [_ id err op]]
   (log/warn "Failed to invoke" op ":" (clj->js err))
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
 (fn [{:keys [db]} _]
   (let [u (ldb/github-user db)]
     {:dispatch [::load-repos (:repos-url u)]})))

(rf/reg-event-fx
 ::load-repos
 (fn [{:keys [db]} [_ url]]
   {:http-xhrio (github/api-request
                 db
                 {:method :get
                  :uri url
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

(rf/reg-event-db
 ::load-orgs--failed
 (fn [db [_ err]]
   (db/set-repo-alerts db
                       [{:type :danger
                         :message (str "Unable to fetch user orgs from Github: " (u/error-msg err))}])))

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
