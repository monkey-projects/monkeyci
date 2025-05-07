(ns monkey.ci.entities.build-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities
             [build :as sut]
             [core :as ec]
             [helpers :as eh]]))

(deftest select-build-by-sid
  (testing "finds matching build"
    (eh/with-prepared-db conn
      (let [org (ec/insert-org conn {:name "test org"})
            repo (ec/insert-repo conn
                                 {:display-id "test-repo"
                                  :name "test repo"
                                  :org-id (:id org)})
            build (ec/insert-build conn
                                   {:display-id "test-build"
                                    :idx 1
                                    :repo-id (:id repo)})]
        (is (= build (-> (sut/select-build-by-sid conn
                                                  (:cuid org)
                                                  (:display-id repo)
                                                  (:display-id build))
                         (select-keys (keys build)))))))))
