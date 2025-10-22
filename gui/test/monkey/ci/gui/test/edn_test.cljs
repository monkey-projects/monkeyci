(ns monkey.ci.gui.test.edn-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [monkey.ci.gui.edn :as edn]))

(deftest regex-parsing
  (testing "can parse regexes as received from backend"
    (is (= "/test-regex/" ; JS form of a regex
           (-> "#regex \"test-regex\""
               (edn/read-string)
               str)))))
