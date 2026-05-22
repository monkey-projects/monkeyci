(ns monkey.ci.cli.verify-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest testing is]]
            [monkey.ci.cli.verify :as sut]))

(deftest verify
  (testing "lints clj file"
    (let [[c :as r] (sut/verify (fs/file "dev-resources" "demo"))]
      (is (some? r))
      (is (= :clj (:type c)))
      (is (some? (:details c)))
      (is (= :success (:result c)))))

  (fs/with-temp-dir [dir]
    (testing "yaml"
      (testing "verifies build file"
        (let [src (fs/create-dir (fs/path dir "yaml"))]
          (is (nil? (spit (fs/file src "build.yaml")
                          "id: test-job\nimage: test-img\n")))
          (let [r (sut/verify src)]
            (is (= :success (-> r (first) :result))))))

      (testing "returns parse error"
        (let [src (fs/create-dir (fs/path dir "yaml-failed"))]
          (is (nil? (spit (fs/file src "build.yaml")
                          "[test []]")))
          (let [r (sut/verify src)]
            (is (= :errors (-> r (first) :result)))))))

    (testing "json"
      (testing "verifies build file"
        (let [src (fs/create-dir (fs/path dir "json"))]
          (is (nil? (spit (fs/file src "build.json")
                          "{\"id\": \"test-job\", \"image\": \"test-img\"}\n")))
          (let [r (sut/verify src)]
            (is (= :success (-> r (first) :result))))))

      (testing "returns parse error"
        (let [src (fs/create-dir (fs/path dir "json-failed"))]
          (is (nil? (spit (fs/file src "build.json")
                          "invalid json")))
          (let [r (sut/verify src)]
            (is (= :errors (-> r (first) :result)))))))

    (testing "edn"
      (testing "verifies build file"
        (let [src (fs/create-dir (fs/path dir "edn"))]
          (is (nil? (spit (fs/file src "build.edn")
                          (pr-str {:id "test-job" :image "test-img"}))))
          (let [r (sut/verify src)]
            (is (= :success (-> r (first) :result))))))

      (testing "returns parse error"
        (let [src (fs/create-dir (fs/path dir "edn-failed"))]
          (is (nil? (spit (fs/file src "build.edn")
                          "#invalid edn")))
          (let [r (sut/verify src)]
            (is (= :errors (-> r (first) :result)))))))))
