(ns monkey.ci.gui.apis.github
  "Functions for invoking the github api"
  (:require [monkey.ci.gui.alerts :as a]
            [monkey.ci.gui.apis.common :as c]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(def api-version "2022-11-28")

(defn api-url [path]
  (str "https://api.github.com" path))

(def get-token :github/token)

(defn api-request
  "Builds an xhrio request map to send a request to Github api"
  [db {:keys [path] :as opts}]
  ;; TODO Handle pagination (see the `link` header)
  (cond-> (c/api-request (-> opts
                             (update :token #(or % (get-token db)))
                             (dissoc :path)))
    true (assoc-in [:headers "X-GitHub-Api-Version"] api-version)
    path (assoc :uri (api-url path))))

(def repos ::repos)

(defn set-repos [db r]
  (assoc db repos r))

(def alerts ::alerts)

(defn set-alerts [db a]
  (assoc db alerts a))

(rf/reg-event-fx
 :github/load-repos
 (fn [{:keys [db]} _]
   {:db (-> db
            (set-repos nil)
            (set-alerts [(a/cust-fetch-github-repos)]))
    :dispatch-n [[::load-user-repos]
                 [::load-orgs]]}))

(rf/reg-event-fx
 ::load-user-repos
 (fn [_ _]
   ;; Turns out that this url gives different results than the one in :repos-url
   {:dispatch [::load-repos (api-url "/user/repos")]}))

(rf/reg-event-fx
 ::load-repos
 (fn [{:keys [db]} [_ url]]
   {:http-xhrio (api-request
                 db
                 {:method :get
                  :uri url
                  :params {:type "all"
                           :per_page 50}
                  :on-success [:github/load-repos--success]
                  :on-failure [:github/load-repos--failed]})}))

(rf/reg-event-fx
 ::load-orgs
 (fn [{:keys [db]} _]
   (let [u (ldb/github-user db)]
     {:http-xhrio (api-request
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
    (set-alerts db [(a/cust-user-orgs-failed err)]))))

(rf/reg-event-db
 :github/load-repos--success
 (fn [db [_ new-repos]]
   (let [orig (repos db)
         all (vec (concat orig new-repos))]
     (-> db
         ;; Add to existing repos since we're doing multiple calls
         (set-repos all)
         (set-alerts [(a/cust-github-repos-success (count all))])))))

(rf/reg-event-fx
 :github/load-repos--failed
 (u/req-error-handler-db
  (fn [db [_ err]]
    (set-alerts db [(a/cust-github-repos-failed err)]))))

(u/db-sub :github/alerts alerts)
(u/db-sub :github/repos repos)
