(ns monkey.ci.test.runners-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [monkey.ci.runners :as sut]
            [monkey.ci.test.helpers :as h]))

(deftest build-local
  (testing "when script not found, launches complete event with warnings"
    (is (= {:type :build/completed
            :exit 1
            :result :warning}
           (-> (sut/build-local {:dir "nonexisting"})
               (select-keys [:type :exit :result])))))

  (testing "launches local build event with absolute script and work dirs"
    (let [r (sut/build-local {:dir "examples/basic-clj"})]
      (is (= :build/local (:type r)))
      (is (true? (some-> r :script-dir (io/file) (.isAbsolute))))))

  (testing "passes pipeline to process"
    (is (= "test-pipeline" (-> {:dir "examples/basic-clj"
                                :pipeline "test-pipeline"}
                               sut/build-local
                               :pipeline))))

  (testing "with workdir"
    (testing "passes work dir to process"
      (h/with-tmp-dir base
        (is (true? (.mkdir (io/file base "local"))))
        (is (= base (-> (sut/build-local {:dir "local"
                                          :workdir base})
                        :work-dir)))))
    
    (testing "combine relative script dir with workdir"
      (h/with-tmp-dir base
        (is (true? (.mkdir (io/file base "local"))))
        (is (= (str base "/local") (-> (sut/build-local {:dir "local"
                                                         :workdir base})
                                       :script-dir)))))

    (testing "leave absolute script dir as is"
      (h/with-tmp-dir base
        (is (= base (-> (sut/build-local {:dir base})
                        :script-dir)))))))

(deftest build-completed
  (testing "returns `command/completed` event for each type"
    (->> [:success :warning :error nil]
         (map (fn [t]
                (is (= {:type :command/completed
                        :command :build}
                       (-> (sut/build-completed {:result t})
                           (select-keys [:type :command]))))))
         (doall)))

  (testing "adds exit code"
    (is (= 1 (-> (sut/build-completed {:exit 1})
                 :exit)))))
