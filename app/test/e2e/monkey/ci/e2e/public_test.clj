(ns monkey.ci.e2e.public-test
  "Tests public routes"
  (:require [clojure.test :refer [deftest testing is]]
            [aleph.http :as http]
            [buddy.core.keys :as bck]
            [cheshire.core :as json]
            [monkey.ci.e2e.common :refer [sut-url]]))

(deftest jwks
  (testing "/auth/jwks"
    (let [r (-> (http/get (sut-url "/auth/jwks"))
                (deref))]
      (is (= 200 (:status r)))
      (let [body (-> (:body r)
                     (slurp)
                     (json/parse-string keyword))]
        (is (not-empty body))
        (is (not-empty (:keys body))
            "contains non-empty keys")
        (is (bck/public-key? (-> body
                                 :keys
                                 (first)
                                 (bck/jwk->public-key)))
            "first key contains public key")))))
