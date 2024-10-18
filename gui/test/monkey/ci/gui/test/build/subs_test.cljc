(ns monkey.ci.gui.test.build.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.build.subs :as sut]
            [monkey.ci.gui.loader :as lo]
            [monkey.ci.gui.repo.db :as rdb]
            [monkey.ci.gui.test.fixtures :as f]
            [re-frame.core :as rf]
            [re-frame.db :refer [app-db]]))

(use-fixtures :each f/reset-db)

(rf/clear-subscription-cache!)

(deftest alerts
  (let [a (rf/subscribe [:build/alerts])]
    (testing "exists"
      (is (some? a)))

    (testing "holds alerts from db"
      (is (map? (reset! app-db (db/set-alerts {} ::test-alerts))))
      (is (= ::test-alerts @a)))))

(deftest jobs
  (let [jobs (rf/subscribe [:build/jobs])]
    (testing "exists"
      (is (some? jobs)))

    (testing "returns build jobs as list"
      (is (some? (reset! app-db (db/set-build {} {:script
                                                  {:jobs {"test-job"
                                                          {:id "test-job"
                                                           :status :success}}}}))))
      (is (= [{:id "test-job"
               :status :success}]
             @jobs)))

    (testing "sorts jobs in dependency order"
      (let [job-list [{:id "dep-1" :dependencies ["root"]}
                      {:id "dep-4" :dependencies ["dep-3"]}
                      {:id "dep-3" :dependencies ["dep-1" "dep-2"]}
                      {:id "root"}
                      {:id "dep-2" :dependencies ["root"]}]]
        (is (some? (reset! app-db (db/set-build {} {:script
                                                    {:jobs (->> job-list
                                                                (map (juxt :id identity))
                                                                (into {}))}}))))
        (is (= ["root"
                "dep-1"
                "dep-2"
                "dep-3"
                "dep-4"]
               (map :id @jobs)))))

    (testing "sort jobs correctly for complex tree"
      (let [job-list [{:id "test-app"}
                      {:id "test-gui"}
                      {:id "app-uberjar" :dependencies ["test-app"]}
                      {:id "publish-app" :dependencies ["test-app"]}
                      {:id "release-gui" :dependencies ["test-gui"]}
                      {:id "publish-gui-img" :dependencies ["release-gui"]}
                      {:id "publish-app-img-arm" :dependencies ["app-uberjar"]}
                      {:id "publish-app-img-amd" :dependencies ["app-uberjar"]}
                      {:id "app-img-manifest" :dependencies ["publish-app-img-arm" "publish-app-img-amd"]}
                      {:id "deploy" :dependencies ["app-img-manifest" "publish-gui-img"]}]]
        (is (some? (reset! app-db (db/set-build {} {:script
                                                    {:jobs (->> job-list
                                                                (map (juxt :id identity))
                                                                (into {}))}}))))
        (is (= ["test-app"
                "test-gui"
                "app-uberjar"
                "publish-app"
                "release-gui"
                "publish-app-img-amd"
                "publish-app-img-arm"
                "publish-gui-img"
                "app-img-manifest"
                "deploy"]
               (map :id @jobs)))))))

(deftest loading?
  (let [r (rf/subscribe [:build/loading?])]
    (testing "exists"
      (is (some? r)))

    (testing "returns reloading status from db"
      (is (false? @r))
      (is (map? (reset! app-db (lo/set-loading {} (db/get-id {})))))
      (is (true? @r)))))

(deftest canceling?
  (let [r (rf/subscribe [:build/canceling?])]
    (testing "exists"
      (is (some? r)))

    (testing "returns canceling status from db"
      (is (false? @r))
      (is (map? (reset! app-db (db/mark-canceling {}))))
      (is (true? @r)))))

(deftest retrying?
  (let [r (rf/subscribe [:build/retrying?])]
    (testing "exists"
      (is (some? r)))

    (testing "returns retrying status from db"
      (is (false? @r))
      (is (map? (reset! app-db (db/mark-retrying {}))))
      (is (true? @r)))))
