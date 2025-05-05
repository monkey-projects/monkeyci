(ns monkey.ci.gui.test.breadcrumb-test
  (:require #?(:clj [clojure.test :refer [deftest testing is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest testing is use-fixtures]])
            [day8.re-frame.test :as rft]
            [monkey.ci.gui.breadcrumb :as sut]
            [monkey.ci.gui.org.db :as cdb]
            [monkey.ci.gui.routing :as r]
            [monkey.ci.gui.test.fixtures :as f]
            [monkey.ci.gui.test.helpers :as h]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(rf/clear-subscription-cache!)

(deftest breadcrumb
  (testing "renders breadcrumb component"
    (is (vector? (sut/breadcrumb
                  [{:url "http://parent"
                    :name "parent"}
                   {:name "end"}])))))

(deftest breadcrumb-path-sub
  (let [p (rf/subscribe [:breadcrumb/path])]
    (testing "exists"
      (is (some? p)))
    
    (testing "only home when no matching path"
      (is (= [{:url "/"
               :name "Home"}]
             @p)))

    (testing "home and org when `org-id` in path"
      (reset! app-db (-> {}
                         (r/set-current {:parameters
                                         {:path
                                          {:org-id "test-org"}}})
                         (cdb/set-org {:name "Test org"})))
      (is (= 2 (count @p)))
      (is (= {:url "/o/test-org"
              :name "Test org"}
             (second @p))))

    (testing "includes repository when `repo-id` in path"
      (reset! app-db (-> {}
                         (r/set-current {:parameters
                                         {:path
                                          {:org-id "test-org"
                                           :repo-id "test-repo"}}})
                         (cdb/set-org {:name "Test org"
                                       :repos [{:id "test-repo"
                                                :name "Test repo"}]})))
      (is (= 3 (count @p)))
      (is (= {:url "/o/test-org/r/test-repo"
              :name "Test repo"}
             (last @p))))

    (testing "includes build when `build-id` in path"
      (reset! app-db (-> {}
                         (r/set-current {:parameters
                                         {:path
                                          {:org-id "test-org"
                                           :repo-id "test-repo"
                                           :build-id "test-build"}}})
                         (cdb/set-org {:name "Test org"
                                       :repos [{:id "test-repo"
                                                :name "Test repo"}]})))
      (is (= 4 (count @p)))
      (is (= {:url "/o/test-org/r/test-repo/b/test-build"
              :name "test-build"}
             (last @p))))

    (testing "includes job when `job-id` in path"
      (reset! app-db (-> {}
                         (r/set-current {:parameters
                                         {:path
                                          {:org-id "test-org"
                                           :repo-id "test-repo"
                                           :build-id "test-build"
                                           :job-id "test-job"}}})
                         (cdb/set-org {:name "Test org"
                                       :repos [{:id "test-repo"
                                                :name "Test repo"}]})))
      (is (= 5 (count @p)))
      (is (= {:url "/o/test-org/r/test-repo/b/test-build/j/test-job"
              :name "test-job"}
             (last @p))))

    (testing "applies orgom calculator for params"
      (reset! app-db (-> {}
                         (r/set-current {:parameters
                                         {:path
                                          {:org-id "test-org"}}
                                         :data
                                         {:name :page/org-params}})
                         (cdb/set-org {:name "Test org"
                                       :repos [{:id "test-repo"
                                                :name "Test repo"}]})))
      (is (= 3 (count @p)))
      (is (= {:url "/o/test-org/params"
              :name "Parameters"}
             (last @p))))))

