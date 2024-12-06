(ns monkey.ci.edn-test
  (:require [clojure.test :refer [deftest testing is]]
            [aero.core :as ac]
            [buddy.core.codecs :as codecs]
            [monkey.ci
             [edn :as sut]
             [vault :as vault]
             [version :as v]
             [utils :as u]]
            [monkey.ci.helpers :as h])
  (:import java.io.StringReader))

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
        (is (= o (sut/edn-> (sut/->edn o)))
            (str "Could not convert:" o))))))

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
