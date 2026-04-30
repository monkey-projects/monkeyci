(ns monkey.ci.script.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [monkey.ci.script
             [build :as b]
             [core :as sut]
             [jobs :as j]]))

(defn- action-job [id f]
  {:type :action
   :id id
   :action f})

(defn- dummy-job
  ([r]
   (action-job ::test-job (constantly r)))
  ([]
   (dummy-job b/success)))

(defn- to-json [v]
  (json/generate-string v))

(deftest resolve-jobs
  (testing "invokes fn"
    (let [job (dummy-job)]
      (is (= [job] (sut/resolve-jobs (constantly [job]) {})))))

  (testing "auto-assigns ids to jobs"
    (let [jobs (repeat 10 (action-job nil (constantly ::test)))
          p (sut/resolve-jobs (vec jobs) {})]
      (is (not-empty p))
      (is (every? :id p))
      (is (= (count jobs) (count (distinct (map :id p)))))))

  (testing "assigns id as metadata to function"
    (let [p (sut/resolve-jobs [(action-job nil (constantly ::ok))] {})]
      (is (= 1 (count p)))
      (is (= "job-1" (-> p
                         first
                         j/job-id)))))

  (testing "does not overwrite existing id"
    (is (= ::test-id (-> [(action-job ::test-id
                                      (constantly :ok))]
                         (sut/resolve-jobs {})
                         first
                         j/job-id))))

  (testing "returns jobs as-is"
    (let [jobs (repeatedly 10 dummy-job)]
      (is (= jobs (sut/resolve-jobs jobs {})))))

  (testing "resolves job resolvables"
    (let [job (dummy-job)]
      (is (= [job] (sut/resolve-jobs [(constantly job)] {}))))))

(deftest load-jobs
  (fs/with-temp-dir [dir]
    (testing "loads jobs from yaml file"
      (let [sd (fs/file (fs/create-dir (fs/path dir "yaml")))
            build (b/set-script-dir {} sd)]
        (is (nil? (spit (fs/file (fs/path sd "build.yaml"))
                        "- id: yaml-job\n  image: test-img\n")))
        (let [loaded (sut/load-jobs build {})]
          (is (= 1 (count loaded)))
          (is (= "yaml-job" (-> loaded first j/job-id))))))

    (testing "loads jobs from json file"
      (let [sd (fs/file (fs/create-dir (fs/path dir "json")))
            build (b/set-script-dir {} sd)]
        (is (nil? (spit (fs/file (fs/path sd "build.json"))
                        (to-json [{:id "json-job"
                                   :image "test-img"}]))))
        (let [loaded (sut/load-jobs build {})]
          (is (= 1 (count loaded)))
          (is (= "json-job" (-> loaded first j/job-id))))))

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
        (is (j/container-job? (-> (sut/load-jobs build {})
                                  (first))))))

    (testing "combines jobs loaded from multiple sources")))




