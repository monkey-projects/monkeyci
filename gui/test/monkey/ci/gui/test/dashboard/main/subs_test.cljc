(ns monkey.ci.gui.test.dashboard.main.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.dashboard.main.subs :as sut]
            [monkey.ci.gui.dashboard.main.db :as db]
            [monkey.ci.gui.org.db :as odb]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)
(rf/clear-subscription-cache!)

(deftest recent-builds
  (h/verify-sub [::sut/recent-builds] #(db/set-recent-builds % ::recent) ::recent nil))

(deftest active-builds
  (let [a (rf/subscribe [::sut/active-builds])]
    
    (testing "exists"
      (is (some? a)))

    (testing "empty if no recent builds"
      (is (empty? @a)))
    
    (testing "reformats recent builds"
      (is (some? (swap! app-db
                        (fn [db]
                          (-> db
                              (db/set-recent-builds
                               [{:repo-id "test-repo"
                                 :status "success"
                                 :idx 1234
                                 :git {:ref "refs/heads/main"}
                                 :source "github-app"
                                 :start-time 100
                                 :end-time 200}])
                              (odb/set-org {:id "test-org"
                                            :repos [{:id "test-repo"
                                                     :name "Test repo"}]}))))))
      (is (= [{:repo "Test repo"
               :git-ref "main"
               :trigger-type :push
               :status :success
               :progress 1
               :build-idx 1234
               :elapsed 100}]
             @a)))))
