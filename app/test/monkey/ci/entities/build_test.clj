(ns monkey.ci.entities.build-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.entities
             [build :as sut]
             [core :as ec]
             [helpers :as eh]]))

(deftest select-build-by-sid
  (testing "finds matching build"
    (eh/with-prepared-db conn
      (let [cust (ec/insert-customer conn {:name "test customer"})
            repo (ec/insert-repo conn
                                 {:display-id "test-repo"
                                  :name "test repo"
                                  :customer-id (:id cust)})
            build (ec/insert-build conn
                                   {:display-id "test-build"
                                    :idx 1
                                    :repo-id (:id repo)})]
        (is (= build (-> (sut/select-build-by-sid conn
                                                  (:cuid cust)
                                                  (:display-id repo)
                                                  (:display-id build))
                         (select-keys (keys build)))))))))
