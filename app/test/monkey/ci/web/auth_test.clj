(ns monkey.ci.web.auth-test
  (:require [clojure.test :refer [deftest testing is]]
            [buddy.core.keys :as bk]
            [monkey.ci.storage :as st]
            [monkey.ci.helpers :as h]
            [monkey.ci.web.auth :as sut]
            [ring.mock.request :as mock]))

(deftest generate-secret-key
  (testing "generates random string"
    (is (string? (sut/generate-secret-key)))))

(deftest config->keypair
  (testing "`nil` if no keys configured"
    (is (nil? (sut/config->keypair {}))))

  (testing "returns private and public keys as map"
    (let [rt (-> {:jwk {:private-key "dev-resources/test/jwk/privkey.pem"
                        :public-key "dev-resources/test/jwk/pubkey.pem"}}
                  (sut/config->keypair))]
      (is (map? rt))
      (is (= 2 (count rt)))
      (is (bk/private-key? (:priv rt)))
      (is (bk/public-key? (:pub rt))))))

(deftest secure-ring-app
  (testing "verifies bearer token using public key and puts user in request `:identity`"
    (h/with-memory-store st
      (let [app :identity
            kp (sut/generate-keypair)
            rt {:storage st
                :jwk (sut/keypair->rt kp)}
            sec (sut/secure-ring-app app rt)
            req (h/->req rt)]
        (is (st/sid? (st/save-user st {:type "github"
                                       :type-id 456})))
        (is (nil? (sec {}))
            "no identity provided")
        (is (some? (sec (-> req
                            (assoc :headers
                                   {"authorization"
                                    (str "Bearer " (sut/generate-jwt req {:sub "github/456"}))}))))
            "bearer token provided"))))

  (testing "verifies token query param using public key and puts user in request `:identity`"
    (h/with-memory-store st
      (let [app :identity
            kp (sut/generate-keypair)
            rt {:storage st
                :jwk (sut/keypair->rt kp)}
            sec (sut/secure-ring-app app rt)
            req (h/->req rt)]
        (is (st/sid? (st/save-user st {:type "github"
                                       :type-id 456})))
        (is (nil? (sec {}))
            "no identity provided")
        (is (some? (sec (-> req
                            (assoc-in [:query-params "authorization"]
                                      (sut/generate-jwt req {:sub "github/456"})))))
            "bearer token provided")))))

(deftest customer-authorization
    (let [h (constantly ::ok)
          auth (sut/customer-authorization h)]
      (testing "invokes target"
        (testing "if no customer id in request path"
          (is (= ::ok (auth {}))))

        (testing "if identity allows access to customer id"
          (is (= ::ok (auth {:parameters
                             {:path
                              {:customer-id "test-cust"}}
                             :identity
                             {:customers
                              #{"test-cust"}}})))))

      (testing "throws authorization error"
        (testing "if customer id is not in identity"
          (is (thrown? Exception
                       (auth {:parameters
                              {:path
                               {:customer-id "test-cust"}}
                              :identity
                              {:customers #{"other-cust"}}}))))

        (testing "if not authenticated"
          (is (thrown? Exception
                       (auth {:parameters
                              {:path
                               {:customer-id "test-cust"}}})))))))
