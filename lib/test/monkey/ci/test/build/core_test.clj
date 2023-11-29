(ns monkey.ci.test.build.core-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [monkey.ci.build
             [core :as sut]
             [spec :as spec]]))

(defn pipeline? [x]
  (instance? monkey.ci.build.core.Pipeline x))

(deftest failed?
  (testing "true if not successful"
    (is (sut/failed? sut/failure))
    (is (not (sut/failed? sut/success))))

  (testing "false if skipped"
    (is (not (sut/failed? sut/skipped)))))

(deftest success?
  (testing "true if `nil`"
    (is (sut/success? nil))))

(deftest skipped?
  (testing "true if `:skipped`"
    (is (sut/skipped? sut/skipped))
    (is (not (sut/skipped? sut/success)))))

(deftest status?
  (testing "true if the object has a status"
    (is (false? (sut/status? nil)))
    (is (true? (sut/status? sut/success)))
    (is (false? (sut/status? {:something "else"})))))

(deftest pipeline

  (testing "creates pipeline object"
    (is (pipeline? (sut/pipeline {:steps []}))))

  (testing "fails if config not conforming to spec"
    (is (thrown? AssertionError (sut/pipeline {}))))

  (testing "function is valid step"
    (is (s/valid? :ci/step (constantly "ok"))))

  (testing "map is valid step"
    (is (s/valid? :ci/step {:action (constantly "ok")})))

  (testing "accepts container image"
    (let [p {:steps [{:container/image "test-image"
                      :script ["first" "second"]}]}]
      (is (s/valid? :ci/step (-> p :steps (first))))
      (is (pipeline? (sut/pipeline p))))))

(deftest defpipeline
  (testing "declares def with pipeline"
    (let [steps [(constantly :test-step)]]
      (sut/defpipeline test-pipeline steps)
      (is (pipeline? test-pipeline))
      (is (= "test-pipeline" (:name test-pipeline)))
      (is (= steps (:steps test-pipeline)))
      (ns-unalias *ns* 'test-pipeline))))
