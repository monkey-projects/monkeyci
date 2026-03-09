(ns monkey.ci.common.jobs-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.common.jobs :as sut]))

(deftest sort-by-deps
  (testing "sorts jobs in dependency order"
    (let [job-list [{:id "dep-1" :dependencies ["root"]}
                    {:id "dep-4" :dependencies ["dep-3"]}
                    {:id "dep-3" :dependencies ["dep-1" "dep-2"]}
                    {:id "root"}
                    {:id "dep-2" :dependencies ["root"]}]]
      (is (= ["root"
              "dep-1"
              "dep-2"
              "dep-3"
              "dep-4"]
             (->> job-list
                  (sut/sort-by-deps)
                  (map :id))))))

  (testing "sort jobs correctly for complex tree"
    (let [job-list [{:id "test-app"}
                    {:id "test-gui"}
                    {:id "app-uberjar" :dependencies ["test-app"]}
                    {:id "publish-app" :dependencies ["test-app"]}
                    {:id "release-gui" :dependencies ["test-gui"]}
                    {:id "publish-gui-img" :dependencies ["release-gui"]}
                    {:id "publish-app-img-arm" :dependencies ["app-uberjar"]}
                    {:id "publish-app-img-amd" :dependencies ["app-uberjar"]}
                    {:id "app-img-manifest" :dependencies ["publish-app-img-arm"
                                                           "publish-app-img-amd"]}
                    {:id "deploy" :dependencies ["app-img-manifest" "publish-gui-img"]}]]
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
             (->> job-list
                  (sut/sort-by-deps)
                  (map :id)))))))
