(ns monkey.ci.gui.test.repo.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.customer.db :as cdb]
            [monkey.ci.gui.repo.db :as db]
            [monkey.ci.gui.repo.subs :as sut]
            [monkey.ci.gui.test.fixtures :as f]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(rf/clear-subscription-cache!)

(deftest repo-info
  (let [r (rf/subscribe [:repo/info "test-repo"])]
    (testing "exists"
      (is (some? r)))
    
    (testing "returns repo by id from customer"
      (is (nil? @r))
      (is (map? (reset! app-db (cdb/set-customer
                                {}
                                {:repos
                                 [{:id "test-repo"
                                   :name "test repository"}]}))))
      (is (= "test repository" (:name @r))))))

(deftest alerts
  (let [a (rf/subscribe [:repo/alerts])]
    (testing "exists"
      (is (some? a)))

    (testing "holds alerts from db"
      (is (map? (reset! app-db (db/set-alerts {} ::test-alerts))))
      (is (= ::test-alerts @a)))))

(deftest builds
  (let [b (rf/subscribe [:repo/builds])]
    (testing "exists"
      (is (some? b)))

    (testing "holds builds from db"
      (is (map? (reset! app-db (-> {:route/current
                                    {:parameters
                                     {:path
                                      {:customer-id "test-cust"
                                       :repo-id "test-repo"}}}}
                                   (db/set-builds [{:build-id ::test-build}])))))
      (is (= 1 (count @b)))
      (is (= {:build-id ::test-build}
             (select-keys (first @b) [:build-id]))))

    (testing "sorts by start-time descending"
      (is (map? (reset! app-db (db/set-builds {} [{:id "old-build"
                                                   :start-time "2023-12-01"}
                                                  {:id "new-build"
                                                   :start-time "2023-12-22"}
                                                  {:id "intermediate-build"
                                                   :start-time "2023-12-10"}]))))
      (is (= ["new-build" "intermediate-build" "old-build"]
             (->> @b (map :id)))))

    (testing "handles both epoch and string start-times"
      (is (map? (reset! app-db (db/set-builds {} [{:id "old-build"
                                                   :start-time "2024-01-18T09:11:40+01:00"}
                                                  {:id "new-build"
                                                   :start-time 1708433539638}]))))
      (is (= ["new-build" "old-build"]
             (->> @b (map :id)))))

    (testing "`nil` when no builds"
      (is (empty? (reset! app-db {})))
      (is (nil? @b)))))

(deftest repo-latest-build
  (let [l (rf/subscribe [:repo/latest-build])]
    (testing "exists"
      (is (some? l)))

    (testing "returns latest build info from db"
      (is (nil? @l))
      (is (some? (reset! app-db (db/set-latest-build {} ::latest))))
      (is (= ::latest @l)))))

(deftest repo-show-trigger-form
  (let [l (rf/subscribe [:repo/show-trigger-form?])]
    (testing "exists"
      (is (some? l)))

    (testing "returns `show trigger form` flag from db"
      (is (nil? @l))
      (is (some? (reset! app-db (db/set-show-trigger-form {} true))))
      (is (true? @l)))))

(deftest repo-editing
  (let [l (rf/subscribe [:repo/editing])]
    (testing "exists"
      (is (some? l)))

    (testing "returns currently editing repo from db"
      (is (nil? @l))
      (is (some? (reset! app-db (db/set-editing {} ::editing))))
      (is (= ::editing @l)))))

(deftest edit-alerts
  (let [a (rf/subscribe [:repo/edit-alerts])]
    (testing "exists"
      (is (some? a)))

    (testing "holds edit-alerts from db"
      (is (map? (reset! app-db (db/set-edit-alerts {} ::test-edit-alerts))))
      (is (= ::test-edit-alerts @a)))))

(deftest saving?
  (let [a (rf/subscribe [:repo/saving?])]
    (testing "exists"
      (is (some? a)))

    (testing "holds saving state from db"
      (is (map? (reset! app-db (db/set-saving {} true))))
      (is (true? @a)))))
