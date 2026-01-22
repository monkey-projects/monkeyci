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

(deftest update-repo
  (let [repo {:name "Test repo"
              :id "test-repo"
              :org-id (st/new-id)
              :github-id 1234}
        {st :storage :as rt} (trt/test-runtime)]

    (is (some? (st/save-repo st repo)))
    
    (testing "updates repo in storage"
      (let [r (-> rt
                  (h/->req)
                  (assoc-in [:parameters :path] {:org-id (:org-id repo)
                                                 :repo-id (:id repo)})
                  (h/with-body (assoc repo :name "updated repo"))
                  (sut/update-repo)
                  :body)]
        (is (not-empty r))
        (is (= "updated repo" (:name r)))
        (is (= "updated repo" (-> (st/find-repo st [(:org-id repo) (:id repo)])
                                  :name)))))

    (testing "clears github id when `nil`"
      (let [r (-> rt
                  (h/->req)
                  (assoc-in [:parameters :path] {:org-id (:org-id repo)
                                                 :repo-id (:id repo)})
                  (h/with-body (assoc repo :github-id nil))
                  (sut/update-repo)
                  :body)]
        (is (nil? (:github-id r)))
        (is (nil? (-> (st/find-repo st [(:org-id repo) (:id repo)])
                      :github-id)))))))

(deftest list-webhooks
  (h/with-memory-store st
    (testing "lists all webhooks for repo"
      (let [org (h/gen-org)
            repo (-> (h/gen-repo)
                     (assoc :org-id (:id org)))
            wh (-> (h/gen-webhook)
                   (assoc :org-id (:id org)
                          :repo-id (:repo-id repo)))
            rt (-> (trt/test-runtime)
                   (trt/set-storage st))]
        (is (some? (st/save-org st org)))
        (is (some? (st/save-repo st repo)))
        (is (some? (st/save-webhook st wh)))
        (let [r (-> rt
                    (h/->req)
                    (assoc :parameters
                           {:path
                            {:org-id (:id org)
                             :repo-id (:repo-id repo)}})
                    (sut/list-webhooks))
              [f :as m] (:body r)]
          (is (= 200 (:status r)))
          (is (= 1 (count m)))
          (is (= (:id wh) (:id f)))
          (is (not (contains? f :secret-key))
              "removes secret key from result"))))))
