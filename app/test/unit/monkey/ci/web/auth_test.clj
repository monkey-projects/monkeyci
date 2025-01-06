(ns monkey.ci.web.auth-test
  (:require [clojure.test :refer [deftest testing is]]
            [buddy.core.keys :as bk]
            [monkey.ci
             [cuid :as cuid]
             [storage :as st]]
            [monkey.ci.helpers :as h]
            [monkey.ci.web.auth :as sut]
            [monkey.ci.test.runtime :as trt]
            [ring.mock.request :as mock]))

(deftest generate-secret-key
  (testing "generates random string"
    (is (string? (sut/generate-secret-key)))))

(deftest config->keypair
  (let [test-priv "dev-resources/test/jwk/privkey.pem"
        test-pub "dev-resources/test/jwk/pubkey.pem"]
    
    (testing "`nil` if no keys configured"
      (is (nil? (sut/config->keypair {}))))

    (testing "returns private and public keys as map"
      (let [rt (-> {:jwk {:private-key test-priv
                          :public-key test-pub}}
                   (sut/config->keypair))]
        (is (map? rt))
        (is (= 2 (count rt)))
        (is (bk/private-key? (:priv rt)))
        (is (bk/public-key? (:pub rt)))))

    (testing "reads private and public keys from string"
      (let [rt (-> {:jwk {:private-key (slurp test-priv)
                          :public-key (slurp test-pub)}}
                   (sut/config->keypair))]
        (is (map? rt))
        (is (= 2 (count rt)))
        (is (bk/private-key? (:priv rt)))
        (is (bk/public-key? (:pub rt)))))))

(deftest secure-ring-app
  (let [app :identity
        {st :storage :as rt} (trt/test-runtime)
        sec (sut/secure-ring-app app rt)
        req (h/->req rt)]

    (testing "with user token"
      (is (st/sid? (st/save-user st {:type "github"
                                     :type-id 456})))
      (is (nil? (sec {}))
          "no identity provided")
      
      (testing "verifies bearer token using public key and puts user in request `:identity`"
        (is (some? (sec (-> req
                            (assoc :headers
                                   {"authorization"
                                    (str "Bearer " (sut/generate-jwt req {:role sut/role-user
                                                                          :sub "github/456"}))}))))
            "bearer token provided"))

      (testing "verifies token query param using public key and puts user in request `:identity`"
        (is (some? (sec (-> req
                            (assoc-in [:query-params "authorization"]
                                      (sut/generate-jwt req (sut/user-token ["github" "456"]))))))
            "bearer token provided")))

    (testing "with build token, puts build customer and repo in identity"
      (let [[cid rid bid :as sid] (repeatedly 3 cuid/random-cuid)]
        (is (st/sid? (st/save-customer st {:id cid
                                           :name "test customer"
                                           :repos [{:id rid
                                                    :name "test repo"}]})))
        (is (st/sid? (st/save-build st {:customer-id cid
                                        :repo-id rid
                                        :build-id bid})))
        (is (some? (-> req
                       (assoc :headers
                              {"authorization"
                               (str "Bearer " (sut/generate-jwt req (sut/build-token sid)))})
                       (sec)))
            "bearer token provided")))

    (testing "accepts sysadmin token"
      (let [[_ username :as sid] ["sysadmin" "test-admin"]]
        (is (st/sid? (st/save-user st
                                   {:id (cuid/random-cuid)
                                    :type-id username
                                    :type "sysadmin"})))
        (is (= username (-> req
                            (assoc :headers
                                   {"authorization"
                                    (str "Bearer " (sut/generate-jwt req (sut/sysadmin-token sid)))})
                            (sec)
                            :type-id))
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

(deftest sysadmin-authorization
  (let [h (constantly ::ok)
        auth (sut/sysadmin-authorization h)]
    (testing "invokes target"
      (testing "if identity is sysadmin"
        (is (= ::ok (auth {:identity
                           {:type :sysadmin}})))))

    (testing "throws authorization error"
      (testing "if user is not a sysadmin"
        (is (thrown? Exception
                     (auth {:identity
                            {:type :github}}))))

      (testing "if not authenticated"
        (is (thrown? Exception
                     (auth {})))))))

(deftest valid-security?
  (testing "false if nil"
    (is (not (true? (sut/valid-security? nil)))))

  (testing "true if valid"
    ;; Github provided values for testing
    (is (true? (sut/valid-security?
                {:secret "It's a Secret to Everybody"
                 :payload "Hello, World!"
                 :x-hub-signature "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"})))))

(deftest parse-signature
  (testing "nil if nil input"
    (is (nil? (sut/parse-signature nil))))

  (testing "returns signature and algorithm"
    (is (= {:alg :sha256
            :signature "test-value"}
           (sut/parse-signature "sha256=test-value")))))

(deftest sysadmin-token
  (testing "has `sysadmin` role"
    (is (= "sysadmin" (:role (sut/sysadmin-token ["test-admin-user"]))))))
