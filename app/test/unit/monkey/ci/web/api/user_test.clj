(ns monkey.ci.web.api.user-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.storage :as st]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web.api.user :as sut]))

(deftest create-user
  (let [{st :storage :as rt} (trt/test-runtime)
        r (-> (h/->req rt)
              (assoc-in [:parameters :body] {:type "github"
                                             :type-id 124
                                             :name "new user"})
              (sut/create-user))
        uid (get-in r [:body :id])]
    (testing "creates user in storage"
      (is (= 201 (:status r)))
      (is (= 1 (st/count-users st)))
      (is (some? (st/find-user st uid))))

    (testing "creates user settings"
      (is (some? (st/find-user-settings st uid))))))

(deftest update-user
  (testing "updates user in storage"
    (let [{st :storage :as rt} (trt/test-runtime)]
      (is (st/sid? (st/save-user st {:type "github"
                                     :type-id 543
                                     :name "test user"})))
      (is (= 200 (-> (h/->req rt)
                     (assoc :parameters {:path
                                         {:user-type "github"
                                          :type-id 543}
                                         :body
                                         {:name "updated user"}})
                     (sut/update-user)
                     :status)))
      (is (= "updated user" (-> (st/find-user-by-type st [:github 543])
                                :name))))))

(deftest update-user-settings
  (let [{st :storage :as rt} (trt/test-runtime)
        u (h/gen-user)]
    (testing "creates when none exist yet"
      (is (st/sid? (st/save-user st u)))
      (is (= 200 (-> (h/->req rt)
                     (assoc :parameters
                            {:path {:user-id (:id u)}
                             :body {:receive-mailing true}})
                     (sut/update-user-settings)
                     :status)))
      (is (some? (st/find-user-settings st (:id u)))))

    (testing "updates existing"
      (is (st/sid? (st/save-user st u)))
      (is (= 200 (-> (h/->req rt)
                     (assoc :parameters
                            {:path {:user-id (:id u)}
                             :body {:receive-mailing false}})
                     (sut/update-user-settings)
                     :status)))
      (is (false? (-> (st/find-user-settings st (:id u))
                      :receive-mailing))))

    (testing "returns `404 not found` when user does not exist"
      (is (= 404 (-> (h/->req rt)
                     (assoc :parameters
                            {:path {:user-id "nonexisting"}
                             :body {:receive-mailing false}})
                     (sut/update-user-settings)
                     :status))))))

(deftest get-user-settings
  (let [{st :storage :as rt} (trt/test-runtime)
        {uid :id :as u} (h/gen-user)
        req (-> (h/->req rt)
                (assoc-in [:parameters :path :user-id] uid))]
    (testing "returns `404` when user not found"
      (is (= 404 (-> req
                     (sut/get-user-settings)
                     :status))))

    (is (some? (st/save-user st u)))

    (testing "returns empty when user exists but no settings"
      (is (empty? (-> req
                      (sut/get-user-settings)
                      :body))))
    
    (testing "returns existing users settings"
      (let [s {:user-id uid
               :receive-mailing true}]
        (is (some? (st/save-user-settings st s)))
        (is (= s (-> req
                     (sut/get-user-settings)
                     :body)))))))
