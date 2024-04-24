(ns monkey.ci.gui.test.subs-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.subs :as sut]
            [monkey.ci.gui.test.fixtures :as tf]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each tf/reset-db)

(rf/clear-subscription-cache!)

(deftest breadcrumb-path
  (let [p (rf/subscribe [:breadcrumb/path])]
    (testing "exists"
      (is (some? p)))
    
    (testing "only home when no matching path"
      (is (= [{:url "/"
               :name "Home"}]
             @p)))

    (testing "home and customer when `customer-id` in path"
      (reset! app-db (-> {}
                         (r/set-current {:parameters
                                         {:path
                                          {:customer-id "test-cust"}}})
                         (cdb/set-customer {:name "Test customer"})))
      (is (= 2 (count @p)))
      (is (= {:url "/c/test-cust"
              :name "Test customer"}
             (second @p))))

    (testing "includes repository when `repo-id` in path"
      (reset! app-db (-> {}
                         (r/set-current {:parameters
                                         {:path
                                          {:customer-id "test-cust"
                                           :repo-id "test-repo"}}})
                         (cdb/set-customer {:name "Test customer"
                                            :repos [{:id "test-repo"
                                                     :name "Test repo"}]})))
      (is (= 3 (count @p)))
      (is (= {:url "/c/test-cust/r/test-repo"
              :name "Test repo"}
             (last @p))))

    (testing "includes build when `build-id` in path"
      (reset! app-db (-> {}
                         (r/set-current {:parameters
                                         {:path
                                          {:customer-id "test-cust"
                                           :repo-id "test-repo"
                                           :build-id "test-build"}}})
                         (cdb/set-customer {:name "Test customer"
                                            :repos [{:id "test-repo"
                                                     :name "Test repo"}]})))
      (is (= 4 (count @p)))
      (is (= {:url "/c/test-cust/r/test-repo/b/test-build"
              :name "test-build"}
             (last @p))))))
