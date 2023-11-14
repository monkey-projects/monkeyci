(ns monkey.ci.test.logging-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [monkey.ci.logging :as sut]
            [monkey.ci.test.helpers :as h]))

(def file? (partial instance? java.io.File))

(deftest make-logger
  (testing "type `inherit` creates logger that always returns `:inherit`"
    (let [l (sut/make-logger {:type :inherit})]
      (is (= :inherit (l {})))))

  (testing "type `file`"
    (h/with-tmp-dir dir
      
      (testing "creates logger that returns file name"
        (let [l (sut/make-logger {:type :file
                                  :dir dir})
              f (l {} ["test.txt"])]
          (is (file? f))
          (is (= (io/file dir "test.txt") f))))

      (testing "defaults to subdir from context work dir"
        (let [l (sut/make-logger {:type :file})
              f (l {:work-dir dir} ["test.txt"])]
          (is (file? f))
          (is (= (io/file dir "logs" "test.txt") f))))

      (testing "creates parent dir"
        (let [l (sut/make-logger {:type :file
                                  :dir dir})
              f (l {} ["logs" "test.txt"])]
          (is (.exists (.getParentFile f))))))))
