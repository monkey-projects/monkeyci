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
