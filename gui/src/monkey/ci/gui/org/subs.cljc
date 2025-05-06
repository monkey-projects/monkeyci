(ns monkey.ci.gui.org.subs
  (:require [clojure.string :as cs]
            [monkey.ci.gui.apis.bitbucket]
            [monkey.ci.gui.apis.github]
            [monkey.ci.gui.org.db :as db]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :org/create-alerts db/create-alerts)
(u/db-sub :org/creating? db/org-creating?)
(u/db-sub :org/edit-alerts db/edit-alerts)
(u/db-sub :org/group-by-lbl db/get-group-by-lbl)
(u/db-sub :org/repo-filter db/get-repo-filter)
(u/db-sub :org/ext-repo-filter db/get-ext-repo-filter)
(u/db-sub :org/bb-webhooks db/bb-webhooks)

(rf/reg-sub
 :org/info
 :<- [:loader/value db/org]
 identity)

(rf/reg-sub
 :org/loading?
 :<- [:loader/loading? db/org]
 identity)

(rf/reg-sub
 :org/alerts
 (fn [db _]
   (db/get-alerts db)))

(rf/reg-sub
 :org/repos
 :<- [:org/info]
 (fn [ci _]
   (:repos ci)))

(rf/reg-sub
 :org/github-repos
 :<- [:org/repos]
 :<- [:github/repos]
 :<- [:org/ext-repo-filter]
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
 :org/bitbucket-repos
 :<- [:bitbucket/repos]
 :<- [:org/ext-repo-filter]
 :<- [:org/bb-webhooks]
 (fn [[r ef wh] _]
   (let [wh-by-id (group-by (juxt :workspace :repo-slug) wh)
         watched? (->> (keys wh-by-id)
                       (set))]
     (letfn [(matches-filter? [{:keys [name]}]
               (or (nil? ef) (cs/includes? (cs/lower-case name) (cs/lower-case ef))))
             (mark-watched [r]
               (let [id [(get-in r [:workspace :slug]) (:slug r)]]
                 (cond-> r
                   (watched? id)
                   (assoc :monkeyci/watched? true
                          :monkeyci/webhook (->> (get wh-by-id id)
                                                 (first))))))]
       (->> r
            (filter matches-filter?)
            (map mark-watched)
            (sort-by :name))))))

(rf/reg-sub
 :org/recent-builds
 :<- [:loader/value db/org]
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
 :org/stats
 :<- [:loader/value db/stats]
 identity)

(rf/reg-sub
 :org/credits
 :<- [:loader/value db/credits]
 identity)

(rf/reg-sub
 :org/credit-stats
 :<- [:org/stats]
 :<- [:org/credits]
 (fn [[stats creds] _]
   (when (and stats creds)
     {:available (:available creds)
      :consumed (->> (get-in stats [:stats :consumed-credits])
                     (map :credits)
                     (reduce + 0))})))

(rf/reg-sub
 :org/labels
 :<- [:org/info]
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
 :org/grouped-repos
 :<- [:org/repos]
 :<- [:org/group-by-lbl]
 :<- [:org/repo-filter]
 (fn [[repos lbl rf] _]
   (letfn [(matches-filter? [{:keys [name]}]
             (or (empty? rf)
                 (empty? name)
                 (cs/includes? (cs/lower-case name) (cs/lower-case rf))))]
     (->> repos
          (filter matches-filter?)
          (group-by (lbl-value lbl))))))

(u/db-sub ::repo-alerts db/repo-alerts)

(rf/reg-sub
 :org/repo-alerts
 :<- [::repo-alerts]
 :<- [:github/alerts]
 (fn [[ra ga] _]
   (concat ra ga)))

(rf/reg-sub
 :org/latest-build
 (fn [db [_ repo-id]]
   (get (db/get-latest-builds db) repo-id)))
