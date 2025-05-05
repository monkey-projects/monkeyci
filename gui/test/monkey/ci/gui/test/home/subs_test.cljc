(ns monkey.ci.gui.test.home.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.home.subs :as sut]
            [monkey.ci.gui.home.db :as db]
            [monkey.ci.gui.login.db :as ldb]
            [monkey.ci.gui.test.fixtures :as f]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)
(rf/clear-subscription-cache!)

(deftest user-orgs
  (let [uc (rf/subscribe [:user/orgs])]
    (testing "exists"
      (is (some? uc)))

    (testing "returns user orgs"
      (is (nil? @uc))
      (is (some? (reset! app-db (db/set-orgs {} ::orgs))))
      (is (= ::orgs @uc)))))

(deftest alerts
  (let [a (rf/subscribe [:user/alerts])]
    (testing "exists"
      (is (some? a)))

    (testing "returns user alerts"
      (is (nil? @a))
      (is (some? (reset! app-db (db/set-alerts {} ::alerts))))
      (is (= ::alerts @a)))))

(deftest join-alerts
  (let [a (rf/subscribe [:org/join-alerts])]
    (testing "exists"
      (is (some? a)))

    (testing "returns org join alerts"
      (is (nil? @a))
      (is (some? (reset! app-db (db/set-join-alerts {} ::alerts))))
      (is (= ::alerts @a)))))

(deftest org-searching?
  (let [s (rf/subscribe [:org/searching?])]
    (testing "exists"
      (is (some? s)))

    (testing "returns org search status"
      (is (false? @s))
      (is (some? (reset! app-db (db/set-org-searching {} true))))
      (is (true? @s)))))

(deftest org-search-results
  (let [r (rf/subscribe [:org/search-results])]
    (testing "exists"
      (is (some? r)))

    (testing "returns org search results"
      (is (nil? @r))
      (is (some? (reset! app-db (db/set-search-results {} ::results))))
      (is (= ::results @r)))))

(deftest org-join-list
  (let [r (rf/subscribe [:org/join-list])]
    (testing "exists"
      (is (some? r)))

    (testing "contains search results"
      (is (nil? @r))
      (is (some? (reset! app-db (db/set-search-results {} [{:id "test org"}]))))
      (is (= ["test org"] (map :id @r))))

    (testing "sets status to `:joined` if user is already linked"
      (is (some? (reset! app-db (-> {}
                                    (db/set-search-results [{:id "joined-org"}])
                                    (ldb/set-user {:id "test-user"
                                                   :orgs ["joined-org"]})))))
      (is (= :joined (-> @r first :status))))

    (testing "sets status to `:joining` if join request is being sent"
      (let [org-id "test-org"]
        (is (some? (reset! app-db (-> {}
                                      (db/set-search-results [{:id "test-org"}])
                                      (db/mark-org-joining "test-org")))))
        (is (= :joining (-> @r first :status)))))))

(deftest user-join-requests
  (let [r (rf/subscribe [:user/join-requests])]
    (testing "exists"
      (is (some? r)))

    (testing "returns join requests"
      (is (nil? @r))
      (is (some? (reset! app-db (db/set-join-requests {} ::results))))
      (is (= ::results @r)))))

(deftest org-joining?
  (let [org-id "test-org"
        c (rf/subscribe [:org/joining? org-id])]
    (testing "exists"
      (is (some? c)))

    (testing "`true` if currently joining org"
      (is (false? @c))
      (is (some? (reset! app-db (db/mark-org-joining {} org-id))))
      (is (true? @c)))

    (testing "returns all when no org id given"
      (is (= #{org-id} @(rf/subscribe [:org/joining?]))))))
