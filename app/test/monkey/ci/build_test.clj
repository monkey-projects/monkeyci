(ns monkey.ci.build-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.build :as sut]))

(deftest make-build-ctx
  (testing "adds build id"
    (is (re-matches #"build-\d+"
                    (-> (sut/make-build-ctx {})
                        :build-id))))

  (testing "defaults to `main` branch"
    (is (= "main"
           (-> {:config {:args {:git-url "test-url"}}}
               (sut/make-build-ctx)
               :git
               :branch))))

  (testing "takes global work dir as build checkout dir"
    (is (= "global-work-dir"
           (-> {:config {:args {:dir ".monkeci"}
                         :work-dir "global-work-dir"}}
               (sut/make-build-ctx)
               :checkout-dir))))

  (testing "adds pipeline from args"
    (is (= "test-pipeline"
           (-> {:config
                {:args {:pipeline "test-pipeline"}}}
               (sut/make-build-ctx)
               :pipeline))))

  (testing "adds script dir from args, as relative to work dir"
    (is (= "work-dir/test-script"
           (-> {:config {:args {:dir "test-script"}
                         :work-dir "work-dir"}}
               (sut/make-build-ctx)
               :script-dir))))

  (testing "with git opts"
    (testing "sets git opts in build config"
      (is (= {:url "test-url"
              :branch "test-branch"
              :id "test-id"}
             (-> {:config
                  {:args {:git-url "test-url"
                          :branch "test-branch"
                          :commit-id "test-id"}}}
                 (sut/make-build-ctx)
                 :git))))

    (testing "sets script dir to arg"
      (is (= "test-script"
             (-> {:config
                  {:args {:git-url "test-url"
                          :branch "test-branch"
                          :commit-id "test-id"
                          :dir "test-script"}
                   :work-dir "work"}}
                 (sut/make-build-ctx)
                 :script-dir)))))

  (testing "when sid specified"
    (testing "parses on delimiter"
      (is (= ["a" "b" "c"]
             (->> {:config
                   {:args {:sid "a/b/c"}}}
                  (sut/make-build-ctx)
                  :sid
                  (take 3)))))
    
    (testing "adds build id"
      (is (string? (-> {:config
                        {:args {:sid "a/b/c"}}}
                       (sut/make-build-ctx)
                       :sid
                       last))))

    (testing "when sid includes build id, reuses it"
      (let [sid "a/b/c"
            ctx (-> {:config
                     {:args {:sid sid}}}
                    (sut/make-build-ctx))]
        (is (= "c" (:build-id ctx)))
        (is (= "c" (last (:sid ctx)))))))

  (testing "when no sid specified"
    (testing "leaves it unspecified"
      (is (empty? (-> {:config {:args {}}}
                      (sut/make-build-ctx)
                      :sid))))))
