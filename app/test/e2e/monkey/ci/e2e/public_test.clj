(ns monkey.ci.e2e.public-test
  "Tests public routes"
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [monkey.ci.e2e.common :refer [sut-url]]))

(deftest jwks
  (testing "/auth/jwks"
    (is (= 200 (-> (http/get (sut-url "/auth/jwks"))
                   (deref)
                   :status)))))
