(ns monkey.ci.gui.repo.subs
  (:require [clojure.string :as cs]
            [monkey.ci.gui.org.subs]
            [monkey.ci.gui.repo.db :as db]
            [monkey.ci.gui.time :as t]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(rf/reg-sub
 :repo/info
 :<- [:org/info]
 (fn [c [_ repo-id]]
   (db/find-repo-in-org c repo-id)))

(rf/reg-sub
 :repo/builds
 (fn [db _]
   (let [params (get-in db [:route/current :parameters :path])
         maybe-parse (fn [t]
                       ;; Legacy data can contain strings
                       (cond-> t
                         (string? t)
                         (-> t/parse t/to-epoch)))
         ->epoch (fn [b]
                   (update b :start-time maybe-parse))]
     (some->> (db/get-builds db)
              (map ->epoch)
              (sort-by :start-time)
              (reverse)
              (map (partial merge params))))))

(u/db-sub :repo/alerts db/alerts)
(u/db-sub :repo/latest-build db/latest-build)
(u/db-sub :repo/trigger-form db/trigger-form)
(u/db-sub :repo/show-trigger-form? db/show-trigger-form?)
(u/db-sub :repo/edit-alerts db/edit-alerts)
(u/db-sub :repo/editing db/editing)
(u/db-sub :repo/saving? (comp true? db/saving?))
(u/db-sub :repo/deleting? (comp true? db/deleting?))

(rf/reg-sub
 ::edit-url
 :<- [:repo/editing]
 (fn [e _]
   (:url e)))

(defn- extract-repo-name [url]
  (letfn [(drop-ext [v]
            (first (cs/split v #"\.")))]
    (when url
      (-> (cs/split url #"/")
          (last)
          (drop-ext)))))

(rf/reg-sub
 ::edit-name
 :<- [:repo/editing]
 (fn [e _]
   (or (:name e) (extract-repo-name (:url e)))))

(rf/reg-sub
 :builds/init-loading?
 :<- [:loader/init-loading? db/id]
 (fn [l _]
   l))

(rf/reg-sub
 :builds/loaded?
 :<- [:loader/loaded? db/id]
 (fn [l _]
   l))

(defn- avg-elapsed [b]
  (-> (reduce (fn [r {s :start-time e :end-time}]
                (+ r (- e s)))
              0
              b)
      (/ (count b))
      (int)))

(defn- success-rate [b]
  (-> (->> b
           (filter (comp (partial = :success) :status))
           (count))
      (/ (count b))
      ;; Round to 0.1%
      (* 1000)
      (int)
      (/ 1000)))

(rf/reg-sub
 :repo/stats
 :<- [:repo/builds]
 (fn [b _]
   (let [d (filter (every-pred :start-time :end-time) b)]
     (when-not (empty? d)
       {:avg-elapsed (avg-elapsed d)
        :success-rate (success-rate d)}))))
