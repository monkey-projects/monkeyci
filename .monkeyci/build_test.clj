(ns build-test
  (:require [clojure.test :refer [deftest testing is]]
            [build :as sut]
            [monkey.ci.build
             [core :as bc]
             [v2 :as b]]
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

(deftest test-gui
  (testing "creates job if gui files changed"
    (is (b/container-job?
         (-> mt/test-ctx
             (mt/with-changes (mt/modified ["gui/shadow-cljs.edn"]))
             (sut/test-gui)))))
  
  (testing "does not create job if gui files unchanged"
    (is (nil?
         (-> mt/test-ctx
             (sut/test-gui))))))

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

(deftest deploy
  (testing "creates job if on main branch and code has changed"
    (let [ctx (-> mt/test-ctx
                  (mt/with-git-ref "refs/heads/main")
                  (mt/with-changes (mt/modified ["app/deps.edn"])))]
      (is (some? (sut/deploy ctx)))))

  (testing "does not create job if on main branch but no code has changed"
    (let [ctx (-> mt/test-ctx
                  (mt/with-git-ref "refs/heads/main"))]
      (is (nil? (sut/deploy ctx)))))  

  (testing "does not create job if not on main branch"
    (let [ctx (-> mt/test-ctx
                  (mt/with-git-ref "refs/heads/other"))]
      (is (nil? (sut/deploy ctx)))))

  (testing "does not create job if releasing"
    (let [ctx (-> mt/test-ctx
                  (mt/with-git-ref "refs/tags/0.1.0"))]
      (is (nil? (sut/deploy ctx))))))

(deftest build-gui-image
  (mt/with-build-params {}
    (testing "no jobs if no release or gui not changed on main"
      (is (nil? (-> mt/test-ctx
                    (sut/build-gui-image)))))

    (testing "on main branch"
      (testing "with gui changed"
        (let [ctx (-> mt/test-ctx
                      (mt/with-git-ref "refs/heads/main")
                      (mt/with-changes (mt/modified ["gui/deps.edn"])))
              jobs (sut/build-gui-image ctx)
              job-ids (set (map b/job-id jobs))]
          (testing "creates publish job for each arch"
            (doseq [a sut/archs]
              (is (contains? job-ids (str "publish-gui-img-" (name a))))))

          (testing "creates manifest job"
            (is (contains? job-ids "gui-img-manifest"))))))))

(deftest prepare-scw-gui-config
  (testing "nothing if no gui changes"
    (is (nil? (sut/prepare-scw-gui-config mt/test-ctx))))

  (testing "with gui changes"
    (let [ctx (-> mt/test-ctx
                  (mt/with-git-ref "refs/heads/main")
                  (mt/with-changes (mt/modified ["gui/deps.edn"])))
          job (sut/prepare-scw-gui-config ctx)]
      (testing "creates action job"
        (is (b/action-job? job)))

      (testing "provides artifacts"
        (is (not-empty (:save-artifacts job))))

      (testing "creates config files from params"
        (mt/with-tmp-dir dir
          (mt/with-build-params {"scw-gui-config" "test-gui-config"
                                 "scw-gui-admin-config" "test-admin-config"}
            (is (bc/success? @(mt/execute-job job (mt/with-checkout-dir ctx dir)))))))

      (testing "fails when no config found"
        (mt/with-tmp-dir dir
          (mt/with-build-params {}
            (is (bc/failed? @(mt/execute-job job (mt/with-checkout-dir ctx dir))))))))))

(deftest jobs
  (mt/with-build-params {}
    (testing "with release tag"
      (let [jobs (mt/resolve-jobs
                  sut/jobs
                  (-> mt/test-ctx
                      (mt/with-git-ref "refs/tags/0.16.4")))
            ids (set (map b/job-id jobs))]
        (testing "contains pushover job"
          (is (contains? ids "pushover")))

        (testing "contains gui img publishing job"
          (is (contains? ids "publish-gui-img-amd")))

        (testing "contains gui img manifest job"
          (is (contains? ids "gui-img-manifest")))))))
