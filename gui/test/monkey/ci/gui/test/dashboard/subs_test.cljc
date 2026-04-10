(ns monkey.ci.gui.test.dashboard.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.dashboard.db :as db]
            [monkey.ci.gui.dashboard.subs :as sut]
            [monkey.ci.gui.org.db :as odb]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(rf/clear-subscription-cache!)

(use-fixtures :each f/reset-db)

(deftest recent-builds
  (let [r (rf/subscribe [::sut/recent-builds])]
    (testing "exists"
      (is (some? r)))

    (testing "holds recent builds"
      (is (empty? @r))
      (is (some? (reset! app-db (db/set-recent-builds {} [{:build-id "test-build"}]))))
      (is (= 1 (count @r))))

    (testing "adds repo name"
      (is (some? (reset! app-db
                         (-> {}
                             (db/set-recent-builds [{:build-id "test-build"
                                                     :repo-id "test-repo"}])
                             (odb/set-org {:id "test-org"
                                           :repos [{:id "test-repo"
                                                    :name "test repo"}]})))))
      (is (= "test repo"
             (-> @r
                 first
                 :repo-name))))))

(deftest active-repos
  (let [a (rf/subscribe [::sut/active-repos])]
    (testing "exists"
      (is (some? a)))

    (testing "provides repo names and build count"
      (is (some? (reset! app-db
                         (-> {}
                             (db/set-recent-builds [{:repo-id "test-repo"}
                                                    {:repo-id "test-repo"}])
                             (odb/set-org {:repos [{:id "test-repo"
                                                    :name "test repo"}]})))))
      (is (= [{:repo "test repo"
               :builds 2}]
             @a)))))
