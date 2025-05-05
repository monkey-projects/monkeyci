(ns monkey.ci.gui.test.params.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures async]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.params.db :as db]
            [monkey.ci.gui.params.events :as e]
            [monkey.ci.gui.params.subs :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(rf/clear-subscription-cache!)

(deftest org-params
  (h/verify-sub [:org/params]
                #(db/set-params % [::test-params])
                [::test-params]
                nil))

(deftest org-param
  (let [id (random-uuid)
        cp (rf/subscribe [:org/param id 0])]
    (testing "exists"
      (is (some? cp)))

    (testing "returns org param in set"
      (is (some? (reset! app-db (db/set-editing {} id {:id id
                                                       :parameters
                                                       [{:name "param name"
                                                         :value "param value"}]}))))
      (is (= {:name "param name"
              :value "param value"}
             @cp)))))

(deftest params-alerts
  (h/verify-sub [:params/alerts]
                #(db/set-alerts % ::test-alerts)
                ::test-alerts
                nil))

(deftest params-loading?
  (h/verify-sub [:params/loading?]
                #(db/mark-loading %)
                true
                false))

(deftest params-saving?
  (let [id (random-uuid)]
    (h/verify-sub [:params/saving? id]
                  #(db/mark-saving % id)
                  true
                  false)))

(deftest params-set-deleting?
  (let [id (random-uuid)]
    (h/verify-sub [:params/set-deleting? id]
                  #(db/mark-set-deleting % id)
                  true
                  false)))

(deftest params-set-alerts
  (let [id (random-uuid)]
    (h/verify-sub [:params/set-alerts id]
                  #(db/set-set-alerts % id ::test-alerts)
                  ::test-alerts
                  nil)))

(deftest params-editing?
  (let [id (random-uuid)
        params {:id id
                :parameters [{:name "key"
                              :value "value"}]}]
    (h/verify-sub [:params/editing? id]
                  #(db/set-editing % id params)
                  true
                  false)))

(deftest params-editing
  (let [id (random-uuid)
        params {:id id
                :parameters [{:name "key"
                              :value "value"}]}]
    (h/verify-sub [:params/editing id]
                  #(db/set-editing % id params)
                  params
                  nil)))

(deftest new-sets
  (let [s (rf/subscribe [:params/new-sets])]
    (testing "exists"
      (is (some? s)))

    (testing "provides all new editing sets"
      (is (empty? @s))
      (is (some? (reset! app-db (-> {}
                                    (db/set-editing (db/new-temp-id) ::new-set)
                                    (db/set-editing "existing-set" ::existing-set)))))
      (is (= [::new-set] @s)))))
