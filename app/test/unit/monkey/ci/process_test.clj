(ns monkey.ci.process-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.process :as sut]))

(deftest add-logback-config
  (testing "adds jvm opts to alias"
    (is (= ["-Dlogback.configurationFile=/test/path"]
           (-> {:aliases
                {::test
                 {:main-opts []}}}
               (sut/add-logback-config ::test
                                       "/test/path")
               (get-in [:aliases ::test :jvm-opts])))))

  (testing "adds jvm opts to existing"
    (is (= ["-Xmx1g"
            "-Dlogback.configurationFile=/test/path"]
           (-> {:aliases
                {::test
                 {:main-opts []
                  :jvm-opts ["-Xmx1g"]}}}
               (sut/add-logback-config ::test
                                       "/test/path")
               (get-in [:aliases ::test :jvm-opts]))))))
