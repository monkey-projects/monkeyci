(ns monkey.ci.test.podman-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.process :as bp]
            [monkey.ci
             [containers :as mcc]
             [podman :as sut]]
            [monkey.ci.test.helpers :as h]))

(deftest run-container
  (testing "starts podman process"
    (with-redefs [bp/process (fn [args]
                               (future args))]
      (h/with-tmp-dir dir
        (let [r (mcc/run-container
                 {:containers {:type :podman}
                  :build-id "test-build"
                  :work-dir dir
                  :step {:name "test-step"
                         :container/image "test-img"
                         :script ["first" "second"]}})]
          (is (map? r))
          (is (= "/usr/bin/podman" (-> r :cmd first)))
          (is (contains? (set (:cmd r))
                         "test-img"))
          (is (= "first && second" (last (:cmd r)))))))))
