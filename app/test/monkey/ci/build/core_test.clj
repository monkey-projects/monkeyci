(ns monkey.ci.build.core-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [monkey.ci.jobs :as j]
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

(deftest map->job
  (testing "converts map with action into action job"
    (is (sut/action-job? (sut/map->job {:action (constantly sut/success)}))))

  (testing "converts map with container into container job"
    (is (sut/container-job? (sut/map->job {:container/image "test-img"}))))

  (testing "maps image"
    (is (= "test-img" (-> {:container/image "test-img"}
                          (sut/map->job)
                          :image)))))

(deftest pipeline
  (testing "creates pipeline object"
    (is (pipeline? (sut/pipeline {:jobs []}))))

  (testing "fails if config not conforming to spec"
    (is (thrown? AssertionError (sut/pipeline {:steps "invalid"}))))

  (testing "function is valid job"
    (is (s/valid? :ci/job (constantly "ok"))))

  (testing "map is valid job"
    (is (s/valid? :ci/job {:action (constantly "ok")})))

  (testing "accepts container image"
    (let [p {:jobs [{:container/image "test-image"
                     :script ["first" "second"]}]}]
      (is (s/valid? :ci/job (-> p :jobs (first))))
      (is (pipeline? (sut/pipeline p)))))

  (testing "converts maps to jobs"
    (let [p (sut/pipeline {:jobs [{:action (constantly "test")}]})]
      (is (sut/action-job? (-> p :jobs first)))))

  (testing "processes steps for backwards compatibility"
    (let [job {:id ::test
               :action (constantly ::test)}
          p (sut/pipeline {:steps [job]})]
      (is (= (:id job) (-> p :jobs first :id))))))

(deftest defpipeline
  (testing "declares def with pipeline"
    (let [jobs [(constantly ::ok)]]
      (sut/defpipeline test-pipeline jobs)
      (is (pipeline? test-pipeline))
      (is (= "test-pipeline" (:name test-pipeline)))
      (is (= 1 (count (:jobs test-pipeline))))
      (ns-unalias *ns* 'test-pipeline))))

(deftest git-ref
  (testing "gets git ref from build context"
    (is (= ::test-ref (sut/git-ref {:build
                                    {:git
                                     {:ref ::test-ref}}})))))

(deftest branch
  (testing "gets branch from context"
    (is (= "test-branch"
           (sut/branch {:build
                        {:git
                         {:ref "refs/heads/test-branch"}}}))))

  (testing "`nil` when no branch"
    (is (nil? (sut/branch {})))
    (is (nil? (sut/branch {:build {:git {:ref nil}}})))
    (is (nil? (sut/branch {:build {:git {:ref "refs/tags/some-tag"}}})))))

(deftest tag
  (testing "gets tag from context"
    (is (= "test-tag"
           (sut/tag {:build
                        {:git
                         {:ref "refs/tags/test-tag"}}}))))

  (testing "`nil` when no tag"
    (is (nil? (sut/tag {})))
    (is (nil? (sut/tag {:build {:git {:ref nil}}})))
    (is (nil? (sut/tag {:build {:git {:ref "refs/heads/some-branch"}}})))))

(deftest main-branch
  (testing "gets main branch from context"
    (is (= "main" (sut/main-branch {:build
                                    {:git
                                     {:main-branch "main"}}})))))

(deftest main-branch?
  (testing "false if ref branch does not equal main branch"
    (is (false? (sut/main-branch?
                 {:build
                  {:git
                   {:main-branch "main"
                    :ref "refs/heads/other"}}}))))

  (testing "true if ref branch equals main branch"
    (is (true? (sut/main-branch?
                {:build
                 {:git
                  {:main-branch "main"
                   :ref "refs/heads/main"}}})))))

(deftest depends-on
  (testing "adds dependencies to job"
    (is (= [::deps]
           (-> (sut/action-job ::test-job (constantly nil))
               (sut/depends-on [::deps])
               (j/deps)))))

  (testing "adds to existing dependencies"
    (is (= [::orig ::new]
           (-> (sut/action-job ::test-job (constantly nil) {j/deps [::orig]})
               (sut/depends-on [::new])
               (j/deps)))))

  (testing "removes duplicate dependencies"
    (is (= [::orig ::new]
           (-> (sut/action-job ::test-job (constantly nil) {j/deps [::orig ::new]})
               (sut/depends-on [::new])
               (j/deps))))))
