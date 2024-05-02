(ns monkey.ci.web.common-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.web.common :as sut]
            [monkey.ci.helpers :as h]))

(deftest run-build-async
  (testing "dispatches `build/pending` event"
    (let [{:keys [recv] :as e} (h/fake-events)
          rt {:events e
              :runner (constantly :ok)}]
      (is (some? @(sut/run-build-async rt)))
      (is (= 1 (count @recv)))
      (is (= :build/pending (-> @recv first :type))))))
