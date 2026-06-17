(ns monkey.ci.core-test
  (:require [clojure.test :refer :all]
            [monkey.ci.core :as sut]))

(deftest system-invoker
  (testing "creates a fn"
    (is (fn? (sut/system-invoker {} {}))))

  (testing "invokes command with context"
    (let [inv (sut/system-invoker {:command (constantly "test-result")} {})]
      (is (= "test-result" (inv {})))))
  
  (testing "passes config"
    (let [inv (sut/system-invoker {:command identity}
                                  {})
          args {:key "value"}]
      ;; Config is passed to the command
      (is (= args (:args (inv args)))))))

