(ns monkey.ci.gui.test.repo.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.repo.subs :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(rf/clear-subscription-cache!)

(deftest repo-info
  (let [r (rf/subscribe [:repo/info "test-project" "test-repo"])]
    (testing "exists"
      (is (some? r)))
    
    (testing "returns repo by id from customer"
      (is (nil? @r))
      (is (map? (reset! app-db (cdb/set-customer
                                {}
                                {:projects
                                 [{:id "test-project"
                                   :repos
                                   [{:id "test-repo"
                                     :name "test repository"}]}]}))))
      (is (= "test repository" (:name @r))))))
