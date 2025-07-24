(ns monkey.ci.web.api.repo-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.storage :as st]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web.api.repo :as sut]))

(deftest create-repo
  (let [repo {:name "Test repo"
              :org-id (st/new-id)}
        {st :storage :as rt} (trt/test-runtime)]
    
    (testing "generates id from repo name"
      (let [r (-> rt
                  (h/->req)
                  (h/with-body repo)
                  (sut/create-repo)
                  :body)]
        (is (= "test-repo" (:id r)))))

    (testing "on id collision, appends index"
      (let [new-repo {:name "Test repo"
                      :org-id (:org-id repo)}
            r (-> rt
                  (h/->req)
                  (h/with-body new-repo)
                  (sut/create-repo)
                  :body)]
        (is (= "test-repo-2" (:id r)))))))

