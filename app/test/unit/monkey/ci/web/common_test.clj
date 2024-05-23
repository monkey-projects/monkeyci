(ns monkey.ci.web.common-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.web.common :as sut]
            [monkey.ci.helpers :as h]))

(deftest run-build-async
  (testing "dispatches `build/pending` event"
    (let [{:keys [recv] :as e} (h/fake-events)
          rt {:events e
              :runner (constantly :ok)}
          build {:build-id "test-build"
                 :sid ["test" "build"]}]
      (is (some? @(sut/run-build-async rt build)))
      (is (= 1 (count @recv)))
      (let [evt (first @recv)]
        (is (= :build/pending (:type evt)))
        (is (= build (:build evt)))
        (is (= (:sid build) (:sid evt)))))))
