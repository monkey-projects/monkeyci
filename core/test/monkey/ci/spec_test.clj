(ns monkey.ci.spec-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.spec :as sut]))

#_(deftest url?
  (testing "matches valid url"
    (is (sut/url? "http://test")))

  (testing "does not match invalid url"
    (is (not (sut/url? "invalid"))))

  (testing "matches url with query string"
    (is (sut/url? "http://test?key=value&other-key=value"))))
