(ns build-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [build :as sut]
            [clojure.string :as cs]
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

(deftest scw-images
  (testing "`nil` if no images published"
    (is (nil? (sut/scw-images mt/test-ctx))))

  (testing "returns action job"
    (is (bc/action-job? (-> mt/test-ctx
                            (mt/with-git-ref "refs/tags/0.1.0")
                            (sut/scw-images))))))

(deftest build-gui-image
  (mt/with-build-params {}
    (testing "no jobs if no release or gui not changed on main"
      (is (nil? (-> mt/test-ctx
                    (sut/build-gui-image)))))

    (testing "on main branch"
      (testing "with gui changed"
        (let [ctx (-> mt/test-ctx
                      (assoc :archs [:amd])
                      (mt/with-git-ref "refs/heads/main")
                      (mt/with-changes (mt/modified ["gui/deps.edn"])))
              jobs (sut/build-gui-image ctx)
              job-ids (set (map b/job-id jobs))]
          (testing "creates publish job for each arch"
            (doseq [a (b/archs ctx)]
              (is (contains? job-ids (str "publish-gui-img-" (name a))))))

          (testing "creates manifest job"
            (is (contains? job-ids "gui-img-manifest"))))))))

(deftest upload-uberjar
  (testing "`nil` if no uberjar published"
    (is (nil? (sut/upload-uberjar mt/test-ctx))))

  (testing "when uberjar published"
    (let [job (-> mt/test-ctx
                  (mt/with-git-ref "refs/heads/main")
                  (mt/with-changes (mt/modified ["app/deps.edn"]))
                  (sut/upload-uberjar))]
      (testing "returns action job"
        (is (bc/action-job? job)))

      (mt/with-build-params {"s3-url" "http://test-url"
                             "s3-bucket" "test-bucket"
                             "s3-access-key" "test-access"
                             "s3-secret-key" "test-secret"}
        (let [inv (atom nil)
              client (atom nil)]
          (with-redefs [sut/make-s3-client (fn [& args]
                                             (reset! client args))
                        sut/put-s3-file (fn [client & args]
                                          (reset! inv args))]
            (testing "puts object to s3 bucket using params"
              (is (bc/success? (mt/execute-job job (-> mt/test-ctx
                                                       (mt/with-git-ref "refs/tags/1.2.3")))))
              (is (some? @inv))
              (is (= ["http://test-url"
                      "test-access"
                      "test-secret"]
                     @client)))

            (testing "adds version to file"
              (is (= ["test-bucket"
                      "monkeyci/release-1.2.3.jar"
                      "app/target/monkeyci-standalone.jar"]
                     @inv)))))))))

(deftest prepare-install-script
  (testing "reads install script and replaces version"
    (let [r (-> mt/test-ctx
                (mt/with-git-ref "refs/tags/0.16.4")
                (assoc-in [:job :work-dir] "..")
                (sut/prepare-install-script))]
      (is (cs/includes? r "VERSION=0.16.4")))))

(deftest jobs
  (mt/with-build-params {}
    (testing "with release tag"
      (let [jobs (mt/resolve-jobs
                  sut/jobs
                  (-> mt/test-ctx
                      (mt/with-git-ref "refs/tags/0.16.4")
                      (assoc :archs [:amd])))
            ids (set (map b/job-id jobs))]
        (testing "contains pushover job"
          (is (contains? ids "pushover")))

        (testing "contains gui img publishing job"
          (is (contains? ids "publish-gui-img-amd")))

        (testing "contains gui img manifest job"
          (is (contains? ids "gui-img-manifest")))

        (testing "contains common publish job"
          (is (contains? ids "publish-common")))))))
