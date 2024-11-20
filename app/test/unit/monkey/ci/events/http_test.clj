(ns monkey.ci.events.http-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.events.http :as sut]))

(deftest parse-event-line
  (testing "`nil` if invalid"
    (is (nil? (sut/parse-event-line "invalid"))))

  (testing "returns event parsed from edn"
    (is (= {:key "value"}
           (sut/parse-event-line "data: {:key \"value\"}")))))
