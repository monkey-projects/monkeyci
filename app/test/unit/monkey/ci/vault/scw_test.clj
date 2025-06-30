(ns monkey.ci.vault.scw-test
  (:require [clojure.test :refer [deftest testing is]]
            [martian.core :as mc]
            [monkey.ci.vault.scw :as sut]
            [monkey.scw.core :as scw]))

(deftest generate-dek
  (let [config {:region "fr-par"
                :key-id "test-key-id"}
        client (sut/make-client config)]
    (testing "generates encryption key using api"
      (with-redefs [mc/response-for (fn [ctx id opts]
                                      (when (and (= :generate-data-key id)
                                                 (= config opts))
                                        (future {:status 200
                                                 :body
                                                 {:key-id id
                                                  :ciphertext "encrypted-key"
                                                  :plaintext "plain-key"}})))]
        (is (= {:enc "encrypted-key"
                :key "plain-key"}
               @(sut/generate-dek client)))))
        
    (testing "throws on error"
      (with-redefs [mc/response-for (constantly {:status 400})]
        (is (thrown? Exception @(sut/generate-dek client)))))))
