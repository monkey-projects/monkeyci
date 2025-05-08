(ns monkey.ci.build-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [build :as sut]
             [runtime :as rt]
             [utils :as u]]))

(deftest make-build-ctx
  (testing "adds build id"
    (is (re-matches #"local-build-\d+"
                    (-> (sut/make-build-ctx {})
                        :build-id))))

  (testing "defaults to `main` branch"
    (is (= "main"
           (-> {:args {:git-url "test-url"}}
               (sut/make-build-ctx)
               :git
               :branch))))

  (testing "takes global work dir as build checkout dir"
    (is (= "global-work-dir"
           (-> {:args {:dir ".monkeci"}
                :work-dir "global-work-dir"}
               (sut/make-build-ctx)
               :checkout-dir))))

  (testing "adds pipeline from args"
    (is (= "test-pipeline"
           (-> {:args {:pipeline "test-pipeline"}}
               (sut/make-build-ctx)
               :pipeline))))

  (testing "adds script dir from args, as relative to work dir"
    (is (= "work-dir/test-script"
           (-> {:args {:dir "test-script"}
                :work-dir "work-dir"}
               (sut/make-build-ctx)
               sut/script
               :script-dir))))

  (testing "with git opts"
    (testing "sets git opts in build config"
      (is (= {:url "test-url"
              :branch "test-branch"
              :id "test-id"}
             (-> {:args {:git-url "test-url"
                         :branch "test-branch"
                         :commit-id "test-id"}}
                 (sut/make-build-ctx)
                 :git))))

    (testing "sets tag"
      (is (= "test-tag"
             (-> {:args {:git-url "test-url"
                         :tag "test-tag"
                         :commit-id "test-id"}}
                 (sut/make-build-ctx)
                 :git
                 :tag))))

    (testing "sets script dir to arg"
      (is (= "test-script"
             (-> {:args {:git-url "test-url"
                         :branch "test-branch"
                         :commit-id "test-id"
                         :dir "test-script"}
                  :work-dir "work"}
                 (sut/make-build-ctx)
                 sut/script
                 :script-dir)))))

  (testing "when sid specified"
    (testing "parses on delimiter"
      (is (= ["a" "b" "c"]
             (->> {:args {:sid "a/b/c"}}
                  (sut/make-build-ctx)
                  :sid
                  (take 3)))))
    
    (testing "adds build id"
      (is (string? (-> {:args {:sid "a/b/c"}}
                       (sut/make-build-ctx)
                       :sid
                       last))))

    (testing "when sid includes build id, reuses it"
      (let [sid "a/b/c"
            ctx (-> {:args {:sid sid}}
                    (sut/make-build-ctx))]
        (is (= "c" (:build-id ctx)))
        (is (= "c" (last (:sid ctx)))))))

  (testing "when no sid specified"
    (testing "leaves it unspecified"
      (is (empty? (-> {:args {}}
                      (sut/make-build-ctx)
                      :sid))))))

(deftest calc-checkout-dir
  (testing "combines build id with checkout base dir from config"
    (let [rt {:config {:checkout-base-dir "/tmp/checkout-base"}}
          build {:build-id "test-build"}]
      (is (= "/tmp/checkout-base" ((rt/from-config :checkout-base-dir) rt)))
      (is (= "/tmp/checkout-base/test-build"
             (sut/calc-checkout-dir rt build))))))

(deftest job-work-dir
  (testing "returns job work dir as absolute path"
    (is (= "/job/work/dir"
           (sut/job-work-dir {:work-dir "/job/work/dir"}
                             {}))))

  (testing "returns checkout dir when no job dir given"
    (is (= "/checkout/dir"
           (sut/job-work-dir {}
                             "/checkout/dir"))))

  (testing "returns current working dir when no job or checkout dir given"
    (is (= (u/cwd)
           (sut/job-work-dir {} nil))))

  (testing "returns work dir as relative dir of checkout dir"
    (is (= "/checkout/job-dir"
           (sut/job-work-dir {:work-dir "job-dir"}
                             "/checkout")))))

(deftest build-end-evt
  (testing "creates build/end event with status completed"
    (let [build {:build-id "test-build"}
          evt (sut/build-end-evt build 0)]
      (is (= :build/end (:type evt)))
      (is (= "test-build" (get-in evt [:build :build-id])))))

  (testing "sets status according to exit"
    (is (= :success
           (-> (sut/build-end-evt {} 0)
               (get-in [:build :status]))))
    (is (= :error
           (-> (sut/build-end-evt {} 1)
               (get-in [:build :status])))))

  (testing "error status when no exit"
    (is (= :error (-> (sut/build-end-evt {} nil)
                      (get-in [:build :status]))))))

(deftest calc-credits
  (testing "zero if no jobs"
    (is (zero? (sut/calc-credits {}))))

  (testing "sum of consumed credits for all jobs"
    (let [build {:script
                 {:jobs
                  {:job-1 {:id "job-1"
                           :start-time 0
                           :end-time 60000
                           :credit-multiplier 3}
                   :job-2 {:id "job-2"
                           :start-time 0
                           :end-time 120000
                           :credit-multiplier 4}}}}]
      (is (= 11 (sut/calc-credits build)))))

  (testing "rounds up"
    (let [build {:script
                 {:jobs
                  {:job-1 {:id "job-1"
                           :start-time 0
                           :end-time 10000
                           :credit-multiplier 3}
                   :job-2 {:id "job-2"
                           :start-time 0
                           :end-time 20000
                           :credit-multiplier 4}}}}]
      (is (= 2 (sut/calc-credits build))))))

(deftest sid
  (testing "returns `:sid` from build"
    (is (= ::test-sid (sut/sid {:sid ::test-sid}))))

  (testing "when no `sid`, returns customer, repo and build id"
    (is (= [::test-cust ::test-repo ::test-build]
           (sut/sid {:org-id ::test-cust
                     :repo-id ::test-repo
                     :build-id ::test-build})))))
