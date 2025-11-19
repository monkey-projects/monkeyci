(ns build-test
  (:require [clojure.test :refer [deftest testing is]]
            [build :as sut]
            [clojars :as clojars]
            [minio :as minio]
            [clojure.string :as cs]
            [monkey.ci.api :as m]
            [monkey.ci.build.core :as bc]
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

(deftest test-app
  (testing "creates job if app files changed"
    (is (m/container-job?
         (-> mt/test-ctx
             (mt/with-changes (mt/modified ["app/deps.edn"]))
             (sut/test-app)))))

  (testing "is dependent on `publish-common` if included"
    (is (= ["publish-common"]
           (-> mt/test-ctx
               (mt/with-changes (mt/modified ["app/deps.edn"
                                              "common/deps.edn"]))
               (mt/with-git-ref ["refs/heads/main"])
               (sut/test-app)
               :dependencies)))))

(deftest test-gui
  (testing "creates job if gui files changed"
    (is (m/container-job?
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
                 second)))))

  (testing "generates 404 error page for release"
    (let [ctx (-> mt/test-ctx
                  (mt/with-git-ref "refs/tags/0.1.0"))]
      (is (= "clojure -X:gen-404"
             (-> (sut/build-gui-release ctx)
                 :script
                 (nth 2)))))))

(deftest publish
  (with-redefs [clojars/latest-version (constantly "1.0.0")]
    (let [ctx (-> mt/test-ctx
                  (mt/with-checkout-dir ".."))]
      (mt/with-build-params {"CLOJARS_USERNAME" "testuser"
                             "CLOJARS_PASSWORD" "testpass"}
        (let [p (-> ctx
                    (mt/with-git-ref "refs/tags/1.2.3")
                    (sut/publish "publish" "app"))
              e (m/env p)]
          (testing "creates container job"
            (is (m/container-job? p)))

          (testing "passes clojars credits to env"
            (is (= "testuser" (get e "CLOJARS_USERNAME")))
            (is (= "testpass" (get e "CLOJARS_PASSWORD"))))

          (testing "passes tag version"
            (is (= "1.2.3" (get e "MONKEYCI_VERSION")))))

        (testing "`nil` if version already published"
          (is (nil? (-> ctx
                        (mt/with-git-ref "refs/tags/1.0.0")
                        (sut/publish "publish-app" "app")))))

        (testing "always publish snapshots"
          (is (m/container-job? (sut/publish ctx "publish-app" "app"))))))))

(deftest scw-images
  (testing "`nil` if no images published"
    (is (nil? (sut/scw-images mt/test-ctx))))

  (testing "returns action job"
    (is (m/action-job? (-> mt/test-ctx
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
              job-ids (set (map m/job-id jobs))]
          (testing "creates publish job for each arch"
            (doseq [a (m/archs ctx)]
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
        (is (m/action-job? job)))

      (mt/with-build-params {"s3-url" "http://test-url"
                             "s3-bucket" "test-bucket"
                             "s3-access-key" "test-access"
                             "s3-secret-key" "test-secret"}
        (let [inv (atom nil)
              client (atom nil)]
          (with-redefs [minio/make-s3-client (fn [& args]
                                               (reset! client args))
                        minio/put-s3-file (fn [client & args]
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

(deftest upload-install-script
  (testing "`nil` if no release"
    (is (nil? (sut/upload-install-script mt/test-ctx)))
    (is (nil? (-> mt/test-ctx
                  (mt/with-git-ref "refs/heads/main")
                  (mt/with-changes (mt/modified ["app/deps.edn"]))
                  (sut/upload-install-script)))))

  (testing "action job on release"
    (is (m/action-job? (-> mt/test-ctx
                           (mt/with-git-ref "refs/tags/0.1.2")
                           (sut/upload-install-script))))))

(deftest jobs
  (with-redefs [clojars/latest-version (constantly "1.0.0")]
    (mt/with-build-params {}
      (testing "with release tag"
        (let [jobs (mt/resolve-jobs
                    sut/jobs
                    (-> mt/test-ctx
                        (mt/with-checkout-dir "..")
                        (mt/with-git-ref "refs/tags/0.16.4")
                        (assoc :archs [:amd])))
              ids (set (map m/job-id jobs))]
          (testing "contains pushover job"
            (is (contains? ids "pushover")))

          (testing "contains gui img publishing job"
            (is (contains? ids "publish-gui-img-amd")))

          (testing "contains gui img manifest job"
            (is (contains? ids "gui-img-manifest")))

          (testing "contains common test job"
            (is (contains? ids "test-common")))

          (testing "contains common publish job"
            (is (contains? ids "publish-common"))))))))
