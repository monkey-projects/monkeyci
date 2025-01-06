(ns build-test
  (:require [clojure.test :refer [deftest testing is]]
            [build :as sut]
            [monkey.ci.test :as mt]))

(deftest tag-version
  (testing "returns valid version"
    (is (= "0.1.0"
           (sut/tag-version {:build
                             {:git
                              {:ref "refs/tags/0.1.0"}}}))))

  (testing "`nil` if not a version number"
    (is (nil?
         (sut/tag-version {:build
                           {:git
                            {:ref "refs/tags/other"}}})))))

(deftest build-gui-release
  (testing "`nil` if no release"
    (is (nil? (sut/build-gui-release mt/test-ctx))))

  (testing "generates index page for release"
    (let [ctx (-> mt/test-ctx
                  (mt/with-git-ref "refs/tags/0.1.0"))]
      (is (= "clojure -X:gen-main"
             (-> (sut/build-gui-release ctx)
                 :script
                 first)))))

  (testing "generates index page for staging"
    (let [ctx (-> mt/test-ctx
                  (mt/with-git-ref "refs/heads/main")
                  (mt/with-changes (mt/modified ["gui/deps.edn"])))]
      (is (= "clojure -X:staging:gen-main"
             (-> (sut/build-gui-release ctx)
                 :script
                 first)))))

  (testing "generates admin page for release"
    (let [ctx (-> mt/test-ctx
                  (mt/with-git-ref "refs/tags/0.1.0"))]
      (is (= "clojure -X:gen-admin"
             (-> (sut/build-gui-release ctx)
                 :script
                 second))))))
