(ns monkey.ci.events.edn-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [manifold.deferred :as md]
            [monkey.ci.events.edn :as sut]
            [monkey.ci.test.helpers :as h])
  (:import [java.io StringReader PipedReader PipedWriter]))

(deftest read-edn
  (let [r (StringReader. "{:type :first}\n{:type :second}\n")
        acc (atom [])
        c (sut/stop-at-eof (partial swap! acc conj))
        s (sut/read-edn r c)]
    (testing "passes events from edn reader to callback"
      (is (not= :timeout (h/wait-until #(= 2 (count @acc)) 500)))
      (is (= [:first :second]
             (map :type @acc))))

    (testing "returns deferred"
      (is (md/deferred? s)))

    (testing "realizes when stream is closed"
      (is (nil? (.close r)))
      (is (not= :timeout (h/wait-until #(md/realized? s) 500)))))

  (testing "reading from file"
    (h/with-tmp-dir dir
      (let [f (io/file dir "test.edn")]
        (is (nil? (spit f "{:type :test}\n")))
        (testing "stops loop when input file is deleted"
          (let [s (sut/read-edn (io/reader f) (sut/stop-on-file-delete (constantly true) f))]
            (is (true? (.delete f)))
            (is (not= :timeout (h/wait-until #(md/realized? s) 500)))))))))
