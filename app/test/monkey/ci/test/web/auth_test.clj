(ns monkey.ci.test.web.auth-test
  (:require [clojure.test :refer [deftest testing is]]
            [buddy.core.keys :as bk]
            [monkey.ci.web.auth :as sut]))

(deftest generate-secret-key
  (testing "generates random string"
    (is (string? (sut/generate-secret-key)))))

(deftest config->keypair
  (testing "`nil` if no keys configured"
    (is (nil? (sut/config->keypair {}))))

  (testing "returns private and public keys as map"
    (let [ctx (-> {:jwk {:private-key "dev-resources/test/jwk/privkey.pem"
                         :public-key "dev-resources/test/jwk/pubkey.pem"}}
                  (sut/config->keypair))]
      (is (map? ctx))
      (is (= 2 (count ctx)))
      (is (bk/private-key? (:priv ctx)))
      (is (bk/public-key? (:pub ctx))))))
