(ns monkey.ci.gui.customer.subs
  (:require [clojure.string :as cs]
            [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :customer/repo-alerts db/repo-alerts)
(u/db-sub ::github-repos db/github-repos)
(u/db-sub :customer/create-alerts db/create-alerts)
(u/db-sub :customer/creating? db/customer-creating?)
(u/db-sub :customer/group-by-lbl db/get-group-by-lbl)
(u/db-sub :customer/repo-filter db/get-repo-filter)
(u/db-sub :customer/github-repo-filter db/get-github-repo-filter)

(rf/reg-sub
 :customer/info
 :<- [:loader/value db/customer]
 identity)

(rf/reg-sub
 :customer/loading?
 :<- [:loader/loading? db/customer]
 identity)

(rf/reg-sub
 :customer/alerts
 (fn [db _]
   (db/get-alerts db)))

(rf/reg-sub
 :customer/repos
 :<- [:customer/info]
 (fn [ci _]
   (:repos ci)))

(rf/reg-sub
 :customer/github-repos
 :<- [:customer/repos]
 :<- [::github-repos]
 :<- [:customer/github-repo-filter]
 (fn [[cr gr f] _]
   (letfn [(watched-repo [r]
             (->> cr
                  ;; This falsely triggers positive when unwatching a repo
                  ;; TODO Add a separate flag for repos that have a registered webhook
                  #_(filter (some-fn (comp (partial = (:id r)) :github-id)
                                     (comp (partial = (:ssh-url r)) :url)
                                     (comp (partial = (:clone-url r)) :url)))
                  (filter (comp (partial = (:id r)) :github-id))
                  first))
           (matches-filter? [{:keys [name]}]
             (or (nil? f) (cs/includes? (cs/lower-case name) (cs/lower-case f))))]
     (->> gr
          (map (fn [r]
                 (let [w (watched-repo r)]
                   (assoc r
                          :monkeyci/watched? (some? w)
                          :monkeyci/repo w))))
          (filter matches-filter?)
          (sort-by :name)))))

(rf/reg-sub
 :customer/recent-builds
 :<- [:loader/value db/customer]
 :<- [:loader/value db/recent-builds]
 (fn [[cust rb] _]
   (let [repos (->> cust
                    :repos
                    (group-by :id))
         add-repo (fn [{:keys [repo-id] :as b}]
                    (assoc b :repo (-> repos (get repo-id) first)))]
     (->> rb
          (sort-by :start-time)
          (reverse)
          (map add-repo)))))

(rf/reg-sub
 :customer/stats
 :<- [:loader/value db/stats]
 identity)

(rf/reg-sub
 :customer/credits
 :<- [:loader/value db/credits]
 identity)

(rf/reg-sub
 :customer/credit-stats
 :<- [:customer/stats]
 :<- [:customer/credits]
 (fn [[stats creds] _]
   (when (and stats creds)
     {:available (:available creds)
      :consumed (->> (get-in stats [:stats :consumed-credits])
                     (map :credits)
                     (reduce + 0))})))

(rf/reg-sub
 :customer/labels
 :<- [:customer/info]
 (fn [cust _]
   (->> cust
        :repos
        (mapcat :labels)
        (map :name)
        (distinct)
        (sort))))

(defn- lbl-value [label]
  (fn [{:keys [labels]}]
    (->> labels
         (filter (comp (partial = label) :name))
         (first)
         :value)))

(rf/reg-sub
 :customer/grouped-repos
 :<- [:customer/repos]
 :<- [:customer/group-by-lbl]
 :<- [:customer/repo-filter]
 (fn [[repos lbl rf] _]
   (letfn [(matches-filter? [{:keys [name]}]
             (or (empty? rf)
                 (empty? name)
                 (cs/includes? (cs/lower-case name) (cs/lower-case rf))))]
     (->> repos
          (filter matches-filter?)
          (group-by (lbl-value lbl))))))
