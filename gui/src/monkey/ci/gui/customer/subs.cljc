(ns monkey.ci.gui.customer.subs
  (:require [monkey.ci.gui.customer.db :as db]
            [monkey.ci.gui.utils :as u]
            [re-frame.core :as rf]))

(u/db-sub :customer/info db/customer)
(u/db-sub :customer/alerts db/alerts)
(u/db-sub :customer/repo-alerts db/repo-alerts)
(u/db-sub :customer/loading? db/loading?)
(u/db-sub ::github-repos db/github-repos)

(rf/reg-sub
 :customer/repos
 :<- [:customer/info]
 (fn [ci _]
   (:repos ci)))

(rf/reg-sub
 :customer/github-repos
 :<- [:customer/repos]
 :<- [::github-repos]
 (fn [[cr gr] _]
   (letfn [(watched-repo [r]
             (some (some-fn (comp (partial = (:id r)) :github-id)
                            (comp (partial = (:ssh-url r)) :url)
                            (comp (partial = (:clone-url r)) :url))
                   cr))]
     (map (fn [r]
            (let [w (watched-repo r)]
              (assoc r
                     :monkeyci/watched? (some? w)
                     :monkeyci/repo r)))
          gr))))
