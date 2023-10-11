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

(deftest build-cmd-args
  (testing "uses `/bin/sh` as default command"
    (let [r (sut/build-cmd-args
             {:build-id "test-build"
              :work-dir "test-dir"
              :step {:name "test-step"
                     :container/image "test-img"
                     :script ["first" "second"]}})]
      (is (= "/bin/sh" (last (drop-last 2 r))))))
  
  (testing "cmd overrides sh command"
    (let [r (sut/build-cmd-args
             {:build-id "test-build"
              :work-dir "test-dir"
              :step {:name "test-step"
                     :container/image "test-img"
                     :container/cmd ["test-entry"]
                     :script ["first" "second"]}})]
      (is (= "test-entry" (last (drop-last r)))))))
