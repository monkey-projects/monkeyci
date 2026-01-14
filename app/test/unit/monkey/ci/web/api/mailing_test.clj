(ns monkey.ci.web.api.mailing-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.storage :as st]
            [monkey.ci.web.api.mailing :as sut]
            [monkey.ci.test
             [helpers :as h]
             [runtime :as trt]]))

(deftest create-mailing
  (let [{st :storage :as rt} (trt/test-runtime)]
    (testing "assigns creation time"
      (let [r (-> rt
                  (h/->req)
                  (assoc-in [:parameters :body] {:subject "test mailing"})
                  (sut/create-mailing))]
        (is (= 201 (:status r)))
        (is (number? (->> r
                          :body
                          :id
                          (st/find-mailing st)
                          :creation-time)))))))

(deftest list-sent-mailings
  (testing "lists sent mailings for mailing"
    (let [m (h/gen-mailing)
          s (-> (h/gen-sent-mailing)
                (assoc :mailing-id (:id m)))
          {st :storage :as rt} (trt/test-runtime)]
      (is (some? (st/save-mailing st m)))
      (is (some? (st/save-sent-mailing st s)))
      (let [r (-> rt
                  (h/->req)
                  (assoc-in [:parameters :path :mailing-id] (:id m))
                  (sut/list-sent-mailings))]
        (is (= 200 (:status r)))
        (is (= [s] (:body r)))))))

(deftest create-sent-mailing
  (let [{st :storage :as rt} (trt/test-runtime)
        m (-> (h/gen-mailing)
              (assoc :subject "Test subject"
                     :text-body "Hi {{EMAIL}}"))]
    (is (some? (st/save-mailing st m)))
    (let [r (-> rt
                (h/->req)
                (assoc :parameters {:path
                                    {:mailing-id (:id m)}
                                    :body
                                    {:other-dests ["test@monkeyci.com"]}})
                (sut/create-sent-mailing))]
      
      (testing "creates in storage"
        (is (= 202 (:status r)) "Returns accepted status")
        (is (= 1 (count (st/list-sent-mailings st (:id m))))))

      (testing "returns created delivery"
        (is (map? (:body r)))
        (is (some? (get-in r [:body :id]))))
      
      (let [mailings (-> rt
                         :mailer
                         :mailings
                         deref)]
        (testing "sends mails in background"
          (is (not= :timeout (h/wait-until #(pos? (count mailings)) 1000))))

        (testing "wraps subject in replacement fn"
          (let [s (-> mailings first :subject)]
            (is (fn? s))
            (is (= "Test subject" (s "test")))))

        (testing "stores delivery"
          (let [d (->> r
                       :body
                       :id
                       (vector (:id m))
                       (st/find-sent-mailing st))]
            (testing "has `sent-at` timestamp"
              (is (number? (:sent-at d))))))

        (testing "substitutes `{{EMAIL}}` with email"
          (let [b (-> mailings first :text-body)]
            (is (= "Hi test-dest"
                   (b "test-dest")))))))))

(deftest list-destinations
  (h/with-memory-store st
    (is (some? (st/save-email-registration st {:id (st/new-id)
                                               :email "test-reg@monkeyci.com"})))
    (is (some? (st/save-user st {:type :test
                                 :type-id (st/new-id)
                                 :email "test-user@monkeyci.com"})))
    (testing "fetches email registrations"
      (is (= ["test-reg@monkeyci.com"]
             (sut/list-destinations st {:to-subscribers true}))))

    (testing "fetches user emails"
      (is (= ["test-user@monkeyci.com"]
             (sut/list-destinations st {:to-users true}))))

    (testing "includes other destinations"
      (is (= ["other@monkeyci.com"]
             (sut/list-destinations st {:other-dests ["other@monkeyci.com"]}))))))
