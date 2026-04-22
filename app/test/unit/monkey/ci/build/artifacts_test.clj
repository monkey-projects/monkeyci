(ns monkey.ci.build.artifacts-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest testing is]]
            [monkey.ci.blob.disk :as blob]
            [monkey.ci.build
             [api :as api]
             [api-server :as bas]
             [artifacts :as sut]]
            [monkey.ci.protocols :as p]
            [monkey.ci.test
             [api-server :as ta]
             [helpers :as h]]))

(deftest build-api-artifact-repository
  (h/with-tmp-dir dir
    (let [store-dir (fs/path dir "store")
          _ (fs/create-dir store-dir)
          store (blob/->DiskBlobStore (str store-dir))
          sid (repeatedly 3 (comp str random-uuid))
          build {:sid sid}
          server (-> (ta/test-config)
                     (assoc :artifacts store
                            :build build)
                     (bas/start-server))
          client (api/make-client (format "http://localhost:%d" (:port server)) (:token server))
          art-id (str (random-uuid))
          in-dir (fs/path dir "input")
          out-dir (fs/path dir "output")
          _ (fs/create-dir in-dir)
          _ (spit (fs/file (fs/path in-dir "test.txt")) "This is a test file")
          repo (sut/make-build-api-repository client)]
      (with-open [s (:server server)]
        (testing "uploads artifact using api"
          (is (= art-id (:artifact-id @(p/save-artifact repo sid art-id (str in-dir))))))
        
        (testing "downloads artifact using api"
          (is (some? @(p/restore-artifact repo sid art-id (str out-dir))))
          (is (fs/exists? (fs/path out-dir "test.txt"))))

        (testing "does nothing if artifact does not exist"
          (is (nil? @(p/restore-artifact repo sid "nonexisting" (str out-dir)))))))))

