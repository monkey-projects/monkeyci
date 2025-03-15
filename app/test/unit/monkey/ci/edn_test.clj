(ns monkey.ci.edn-test
  (:require
   [aero.core :as ac]
   [buddy.core.codecs :as codecs]
   [clojure.test :refer [deftest is testing]]
   [monkey.ci.edn :as sut]
   [monkey.ci.test.helpers :as h]
   [monkey.ci.utils :as u]
   [monkey.ci.vault :as vault]
   [monkey.ci.version :as v])
  (:import
   (java.io StringReader)))

(deftest edn-conversion
  (testing "can convert objects to edn and back"
    (let [objs [{:key "value"}
                "test string"
                (range 10)
                {:events
                 {:type :jms
                  :client {:address "tcp:/test"}}}
                (let [pk (h/generate-private-key)]
                  {:private-key pk})]]
      (doseq [o objs]
        (is (.equals o (sut/edn-> (sut/->edn o)))
            (str "Could not convert: " o)))))

  (testing "can convert regexes"
    (let [re #"test-regex"]
      ;; Need to convert to string, equals is false
      (is (= (str re)
             (str (sut/edn-> (sut/->edn re))))))))

(deftest version
  (testing "replaces `#version` with app version"
    (is (= (str "test-" (v/version))
           (ac/read-config (StringReader. "#join [ \"test-\" #version [] ]"))))))

(deftest aes-key
  (testing "parses to byte array"
    (let [orig (vault/generate-key)
          b64-key (codecs/bytes->b64-str orig)
          parsed (ac/read-config (StringReader. (str "#aes-key \"" b64-key "\"")))]
      (is (= (count orig) (count parsed)))
      (is (java.util.Arrays/equals orig parsed)))))
