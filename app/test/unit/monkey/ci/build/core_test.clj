(ns monkey.ci.build.core-test
  (:require [clojure.test :refer :all]
            [clojure.spec.alpha :as s]
            [monkey.ci.jobs :as j]
            [monkey.ci.build.core :as sut]))

(deftest job-type
  (testing "for v1"
    (is (= :action (sut/job-type {:type :action}))))

  (testing "for v2"
    (is (= :action (sut/job-type (sut/action-job "test-job" (constantly nil)))))))

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

(deftest with-message
  (testing "adds message to result"
    (is (= "test message"
           (-> sut/success
               (sut/with-message "test message")
               :message)))))

(deftest defjob
  (testing "declares var with action job"
    (sut/defjob test-job [_] (constantly ::ok))
    (is (sut/action-job? test-job))
    (is (= "test-job" (sut/job-id test-job)))
    (ns-unalias *ns* 'test-job)))

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
           (-> (sut/action-job ::test-job (constantly nil))
               (sut/depends-on [::orig])
               (sut/depends-on [::new])
               (j/deps)))))

  (testing "removes duplicate dependencies"
    (is (= [::orig ::new]
           (-> (sut/action-job ::test-job (constantly nil) {:dependencies [::orig ::new]})
               (sut/depends-on [::new])
               (j/deps))))))

(deftest file-changes
  (testing "lists added files"
    (let [files [:a :b :c]
          rt {:build
              {:changes
               {:added files}}}]
      (is (= files (sut/files-added rt)))))

  (testing "lists modified files"
    (let [files [:a :b :c]
          rt {:build
              {:changes
               {:modified files}}}]
      (is (= files (sut/files-modified rt)))))

  (testing "lists removed files"
    (let [files [:a :b :c]
          rt {:build
              {:changes
               {:removed files}}}]
      (is (= files (sut/files-removed rt)))))

  (testing "`touched?`"
    (let [rt {:build
              {:changes
               {:added    #{"file-added"}
                :modified #{"file-modified"}
                :removed  #{"file-removed"}}}}]
      (testing "`true` if file added"
        (is (sut/touched? rt "file-added")))

      (testing "`true` if file modified"
        (is (sut/touched? rt "file-modified")))

      (testing "`true` if file removed"
        (is (sut/touched? rt "file-removed")))

      (testing "`false` if file unchanged"
        (is (not (sut/touched? rt "file-unchanged"))))

      (testing "`true` if regex matches"
        (is (sut/touched? rt #"^file-.*$")))

      (testing "`true` if predicate matches"
        (is (sut/touched? rt #(clojure.string/includes? % "add")))))))

(deftest trigger-src
  (testing "returns build source"
    (is (= :api (sut/trigger-src {:build {:source :api}})))))

(deftest job-schema
  (testing "extracts v1 schema"
    (is (= :v1 (sut/job-schema {:schema :v1}))))

  (testing "extracts v2 schema"
    (is (= :v2 (sut/job-schema {:schema :v2}))))

  (testing "v1 by default for legacy purposes"
    (is (= :v1 (sut/job-schema {})))))

