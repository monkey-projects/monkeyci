(ns monkey.ci.utils.io-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [monkey.ci.utils.io :as sut]))

(deftest wait-for-file-test
  (testing "returns the path as soon as the file appears"
    (fs/with-temp-dir [d]
      (let [f    (fs/path d "trigger")
            result (atom nil)]
        ;; Start the wait in a separate thread
        (future
          (reset! result (sut/wait-for-file f :period 50)))
        ;; Create the file after a short delay
        (Thread/sleep 120)
        (fs/create-file f)
        (Thread/sleep 200)
        (is (some? @result))
        (is (= f @result))))))
