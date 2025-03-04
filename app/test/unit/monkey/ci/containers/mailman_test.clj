(ns monkey.ci.containers.mailman-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.containers.mailman :as sut]
            [monkey.ci.protocols :as p]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.mailman :as tm]))

(deftest mailman-container-runner
  (let [m (tm/test-component)
        build (h/gen-build)
        r (sut/->MailmanContainerRunner m build)]
    (testing "is container runner"
      (is (p/container-runner? r)))

    (testing "`run-container` posts `job/pending` event"
      (let [job (h/gen-job)]
        (is (some? (p/run-container r job)))
        (let [evts (-> m
                       :broker
                       (tm/get-posted))]
          (is (= :job/pending (-> evts first :type)))
          (is (= job (-> evts first :job))))))))
