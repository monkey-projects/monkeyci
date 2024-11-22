(ns monkey.ci.events.split-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.events
             [core :as ec]
             [split :as sut]]
            [monkey.ci.helpers :as h]))

(deftest split-events
  (let [in (h/fake-events-receiver)
        out (h/fake-events)
        evt (sut/->SplitEvents in out)]

    (testing "posts to output events"
      (let [e {:type ::test-event}]
        (is (some? (ec/post-events evt e)))
        (is (= [e] (h/received-events out)))))

    (testing "receives from input events"
      (let [ef ::test-filter
            listener (constantly ::ok)]
        (is (some? (ec/add-listener evt ef listener)))
        (is (= 1 (count @(:listeners in))))
        (is (some? (ec/remove-listener evt ef listener)))
        (is (empty? (-> in :listeners deref (get ef))))))))
