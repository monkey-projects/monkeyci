(ns monkey.ci.containers-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.containers :as sut]))

(deftest ctx->container-config
  (testing "extracts all keys with `container` namespace"
    (is (= {:key "value"} (sut/ctx->container-config {:step {:container/key "value"}})))))
