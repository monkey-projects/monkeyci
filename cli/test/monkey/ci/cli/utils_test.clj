(ns monkey.ci.cli.utils-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [monkey.ci.cli.utils :as sut]))

(deftest find-script-dir
  (fs/with-temp-dir [dir]
    (testing "returns `.monkeyci` subdir if it exists"
      (let [p (fs/path dir "exists")
            exp (fs/create-dirs (fs/path p ".monkeyci"))]
        (is (= (str exp) (sut/find-script-dir p)))))

    (testing "returns input dir if no script dir exists"
      (let [p (fs/path dir "notexists")]
        (is (= (str p) (sut/find-script-dir p)))))))
