(ns monkey.ci.web.api.email-reg-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.storage :as st]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]
            [monkey.ci.web.api.email-reg :as sut]))

(deftest create-email-registration
  (let [{st :storage :as rt} (trt/test-runtime)
        email "duplicate@monkeyci.com"
        req (-> rt
                (h/->req)
                (h/with-body {:email email}))]
    (testing "sets creation time"
      (let [r (sut/create-email-registration req)]
        (is (= 201 (:status r)))
        (is (some? (get-in r [:body :creation-time])))))

    (testing "does not create same email twice"
      (is (= 200 (:status (sut/create-email-registration req))))
      (is (= 1 (count (st/list-email-registrations st)))))

    (testing "creates pending confirmation")))

(deftest unregister-email
  (let [{st :storage :as rt} (trt/test-runtime)]
    (testing "with `id` deletes email registration by id"
      (let [reg (h/gen-email-registration)]
        (is (some? (st/save-email-registration st reg)))
        (is (= 200 (-> (h/->req rt)
                       (assoc-in [:parameters :query :id] (:id reg))
                       (sut/unregister-email)
                       :status)))
        (is (nil? (st/find-email-registration st (:id reg))))))
    
    (testing "with `email` deletes email registration by email"
      (let [email "test@monkeyci.com"
            reg (-> (h/gen-email-registration)
                    (assoc :email email))]
        (is (some? (st/save-email-registration st reg)))
        (is (= 200 (-> (h/->req rt)
                       (assoc-in [:parameters :query :email] email)
                       (sut/unregister-email)
                       :status)))
        (is (nil? (st/find-email-registration st (:id reg))))))
    
    (testing "with `email` of user, updates user settings"
      (let [email "testuser@monkeyci.com"
            u (-> (h/gen-user)
                  (assoc :email email))]
        (is (some? (st/save-user st u)))
        (is (= 200 (-> (h/->req rt)
                       (assoc-in [:parameters :query :email] email)
                       (sut/unregister-email)
                       :status)))
        (is (false? (-> (st/find-user-settings st (:id u))
                        :receive-mailing)))))
    
    (testing "with `user-id`, updates user settings"
      (let [u (h/gen-user)]
        (is (some? (st/save-user st u)))
        (is (= 200 (-> (h/->req rt)
                       (assoc-in [:parameters :query :user-id] (:id u))
                       (sut/unregister-email)
                       :status)))
        (is (false? (-> (st/find-user-settings st (:id u))
                        :receive-mailing)))))

    (testing "returns status `204` when no matches found"
      (is (= 204
             (-> (h/->req rt)
                 (assoc-in [:parameters :query :id] "nonexisting")
                 (sut/unregister-email)
                 :status))))

    (testing "returns status `204` when query params"
      (is (= 204
             (-> (h/->req rt)
                 (sut/unregister-email)
                 :status))))))

(deftest confirm-email
  (let [{st :storage :as rt} (trt/test-runtime)
        reg (h/gen-email-registration)]
    (is (some? (st/save-email-registration st reg)))
    
    (testing "400 if invalid code")

    (testing "204 if already confirmed")

    (testing "200 if valid code")

    (testing "404 if no email registration found")))
