(ns monkey.ci.script.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [monkey.ci.script
             [build :as b]
             [config :as c]
             [core :as sut]]))

(deftest run-script
  (fs/with-temp-dir [dir]
    (let [src "dev-resources/test"
          sd (fs/path dir "script")
          build (-> {:build-id "test-build"}
                    (b/set-script-dir sd)
                    (b/set-checkout-dir dir))
          conf (-> {}
                   (c/set-build build)
                   (c/set-api {:url "http://localhost:12342"
                               :token "test-token"}))]
      (is (some? (fs/copy-tree src sd)))

      (let [r (sut/run-script conf)]
        (is (= build (select-keys (:build r) (keys build)))
            "returns input build")
        (is (some? (:jobs r))
            "returns executed jobs")
        
        (testing "connects to configured build api")

        (testing "executes jobs")))))
