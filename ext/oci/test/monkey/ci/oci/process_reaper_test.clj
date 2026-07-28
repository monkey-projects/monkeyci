(ns monkey.ci.oci.process-reaper-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.oci
             [core :as c]
             [process-reaper :as sut]]))

(deftest process-reaper
  (testing "returns empty list when no oci runner"
    (let [r (sut/make-process-reaper {:runner {:type :local}})]
      (is (empty? (r)))))

  (testing "deletes oci stale instances"
    (with-redefs [c/delete-stale-instances (fn [ctx cid]
                                             {:context ctx
                                              :compartment-id cid})]
      (testing "for `:oci` runners"
        (let [r (sut/->ProcessReaper {:runner
                                      {:type :oci
                                       :containers {:user-ocid "test-user"
                                                    :compartment-id "test-comp"}}})
              res (r)]
          (testing "creates context from container config"
            (is (some? (:context res)))
            (is (= "test-comp" (:compartment-id res))))))

      (testing "not for other runners"
        (let [r (sut/->ProcessReaper {:runner {:type :some-other}})]
          (is (empty? (r))))))))

