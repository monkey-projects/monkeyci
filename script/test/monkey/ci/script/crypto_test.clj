(ns monkey.ci.script.crypto-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci
             [cuid :as cuid]
             [vault :as v]]
            [monkey.ci.script.crypto :as sut]
            [taoensso.tempel :as tempel]))

(defn- gen-iv []
  (v/cuid->iv (cuid/random-cuid)))

(deftest encrypt
  (testing "encrypts using iv"
    (let [data "test data"
          iv (gen-iv)
          k (tempel/keychain)
          enc (sut/encrypt (tempel/str->utf8-ba data) iv k)]
      (is (not-empty enc)))))

(deftest decrypt
  (let [data "test data"
        iv (gen-iv)
        k (tempel/keychain)
        enc (sut/encrypt (tempel/str->utf8-ba data) iv k)]
    (testing "decrypts encrypted value"
      (is (= data (tempel/utf8-ba->str (sut/decrypt enc iv k)))))

    (testing "fails with different iv"
      (is (not= data (tempel/utf8-ba->str (sut/decrypt enc (gen-iv) k)))))))
