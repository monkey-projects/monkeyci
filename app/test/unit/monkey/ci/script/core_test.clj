(ns monkey.ci.script.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [monkey.ci.build :as b]
            [monkey.ci.build.core :as bc]
            [monkey.ci.script.core :as sut]
            [monkey.ci.test.helpers :as h]))

(defn dummy-job
  ([r]
   (bc/action-job ::test-job (constantly r)))
  ([]
   (dummy-job bc/success)))

(deftest resolve-jobs
  (testing "invokes fn"
    (let [job (dummy-job)]
      (is (= [job] (sut/resolve-jobs (constantly [job]) {})))))

  (testing "auto-assigns ids to jobs"
    (let [jobs (repeat 10 (bc/action-job nil (constantly ::test)))
          p (sut/resolve-jobs (vec jobs) {})]
      (is (not-empty p))
      (is (every? :id p))
      (is (= (count jobs) (count (distinct (map :id p)))))))

  (testing "assigns id as metadata to function"
    (let [p (sut/resolve-jobs [(bc/action-job nil (constantly ::ok))] {})]
      (is (= 1 (count p)))
      (is (= "job-1" (-> p
                         first
                         bc/job-id)))))

  (testing "does not overwrite existing id"
    (is (= ::test-id (-> [(bc/action-job ::test-id
                                         (constantly :ok))]
                         (sut/resolve-jobs {})
                         first
                         bc/job-id))))

  (testing "returns jobs as-is"
    (let [jobs (repeatedly 10 dummy-job)]
      (is (= jobs (sut/resolve-jobs jobs {})))))

  (testing "resolves job resolvables"
    (let [job (dummy-job)]
      (is (= [job] (sut/resolve-jobs [(constantly job)] {}))))))

(deftest load-jobs
  (h/with-tmp-dir dir
    (testing "loads jobs from yaml file"
      (let [sd (fs/file (fs/create-dir (fs/path dir "yaml")))
            build (b/set-script-dir {} sd)]
        (is (nil? (spit (fs/file (fs/path sd "build.yaml"))
                        "- id: yaml-job\n  image: test-img\n")))
        (let [loaded (sut/load-jobs build {})]
          (is (= 1 (count loaded)))
          (is (= "yaml-job" (-> loaded first bc/job-id))))))

    (testing "loads jobs from json file"
      (let [sd (fs/file (fs/create-dir (fs/path dir "json")))
            build (b/set-script-dir {} sd)]
        (is (nil? (spit (fs/file (fs/path sd "build.json"))
                        (h/to-json [{:id "json-job"
                                     :image "test-img"}]))))
        (let [loaded (sut/load-jobs build {})]
          (is (= 1 (count loaded)))
          (is (= "json-job" (-> loaded first bc/job-id))))))

    (testing "loads jobs from edn file"
      (let [sd (fs/file (fs/create-dir (fs/path dir "edn")))
            build (b/set-script-dir {} sd)]
        (is (nil? (spit (fs/file (fs/path sd "build.edn")) (pr-str [{:id "test-job"
                                                                     :type :container
                                                                     :image "test-img"}]))))
        (is (= 1 (count (sut/load-jobs build {}))))))

    (testing "loads single job from edn file"
      (let [sd (fs/file (fs/create-dir (fs/path dir "edn-single")))
            build (b/set-script-dir {} sd)]
        (is (nil? (spit (fs/file (fs/path sd "build.edn")) (pr-str {:id "test-job"
                                                                    :type :container
                                                                    :image "test-img"}))))
        (is (= 1 (count (sut/load-jobs build {}))))))

    (testing "always container jobs for non-clj files"
      (let [sd (fs/file (fs/create-dir (fs/path dir "edn-3")))
            build (b/set-script-dir {} sd)]
        (is (nil? (spit (fs/file (fs/path sd "build.edn")) (pr-str [{:id "test-job"
                                                                     :image "test-img"}]))))
        (is (bc/container-job? (-> (sut/load-jobs build {})
                                   (first))))))

    (testing "combines jobs loaded from multiple sources")))

(deftest verify
  (testing "lints clj file"
    (let [[c :as r] (sut/verify (fs/file "examples" "basic-clj"))]
      (is (some? r))
      (is (= :clj (:type c)))
      (is (some? (:details c)))
      (is (= :success (:result c)))))

  (h/with-tmp-dir dir
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
