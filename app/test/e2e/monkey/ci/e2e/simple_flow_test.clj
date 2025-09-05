(ns monkey.ci.e2e.simple-flow-test
  "Basic flow tests"
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [monkey.ci.e2e.common :refer [sut-url]]))

(deftest new-user)
