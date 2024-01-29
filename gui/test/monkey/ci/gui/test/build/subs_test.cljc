(ns monkey.ci.gui.test.build.subs-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            [monkey.ci.gui.build.db :as db]
            [monkey.ci.gui.build.subs :as sut]
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

(deftest logs
  (let [l (rf/subscribe [:build/logs])]
    (testing "exists"
      (is (some? l)))

    (testing "returns logs from db"
      (is (nil? @l))
      (is (map? (reset! app-db (db/set-logs {} ::test-logs))))
      (is (= ::test-logs @l)))))

(deftest details
  (let [d (rf/subscribe [:build/details])]
    (testing "exists"
      (is (some? d)))

    (testing "returns build details from build list according to current route"
      (is (nil? @d))
      (is (some? (reset! app-db (-> {}
                                    (rdb/set-builds
                                     [{:id "some-build"}
                                      {:id "this-build"
                                       :message "Test build"}])
                                    (assoc :route/current
                                           {:parameters
                                            {:path
                                             {:build-id "this-build"}}})))))
      (is (= {:id "this-build"
              :message "Test build"}
             (select-keys @d [:id :message]))))

    (testing "adds logs for step according to path"
      (is (map? (reset! app-db (-> {}
                                   (rdb/set-builds
                                    [{:id "test-build"
                                      :pipelines
                                      [{:index 0
                                        :name "test-pipeline"
                                        :steps
                                        [{:index 0}]}]}])
                                   (db/set-logs
                                    [{:name "test-pipeline/0/out.txt"
                                      :size 100}
                                     {:name "test-pipeline/0/err.txt"
                                      :size 50}])
                                   (assoc :route/current
                                          {:parameters
                                           {:path
                                            {:build-id "test-build"}}})))))
      (is (= [{:name "out.txt" :size 100 :path "test-pipeline/0/out.txt"}
              {:name "err.txt" :size 50 :path "test-pipeline/0/err.txt"}]
             (-> @d :pipelines first :steps first :logs))))))

(deftest reloading?
  (let [r (rf/subscribe [:build/reloading?])]
    (testing "exists"
      (is (some? r)))

    (testing "returns reloading status from db"
      (is (false? @r))
      (is (map? (reset! app-db (db/set-reloading {}))))
      (is (true? @r)))))
