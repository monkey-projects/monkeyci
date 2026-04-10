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
      (is (= [{:repo {:repo-id "test-repo"
                      :repo-name "test repo"}
               :builds 2}]
             @a)))))

(deftest successful-builds
  (let [s (rf/subscribe [::sut/successful-builds])]
    (testing "exists"
      (is (some? s)))

    (testing "zero when no builds"
      (is (= 0 @s)))

    (testing "calculates success ratio of finished builds"
      (is (some? (reset! app-db
                         (db/set-recent-builds {}
                                               [{:status :success}
                                                {:status :error}
                                                {:status :running}]))))
      (is (= 0.5 @s)))))

(deftest avg-duration
  (let [d (rf/subscribe [::sut/avg-duration])]
    (testing "exists"
      (is (some? d)))

    (testing "zero when no builds"
      (is (= 0 @d)))

    (testing "calculates average elapsed time of finished builds"
      (is (some? (reset! app-db
                         (db/set-recent-builds {}
                                               [{:status :success
                                                 :start-time 1000
                                                 :end-time 2000}
                                                {:status :error
                                                 :start-time 3000
                                                 :end-time 5000}
                                                {:status :running}]))))
      (is (= 1500 @d)))))
