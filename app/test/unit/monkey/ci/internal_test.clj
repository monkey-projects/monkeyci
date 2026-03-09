(ns monkey.ci.internal-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.internal :as sut]))

(deftest main-test
  (binding [*err* (java.io.StringWriter.)]
    (with-redefs [clojure.core/shutdown-agents (constantly nil)
                  cli-matic.platform/exit-script (constantly :exit)]
      (testing "runs cli"
        (is (= :exit (sut/-main "-?")))))))
