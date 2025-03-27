(ns monkey.ci.events.mailman.jms-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.events.mailman.jms :as sut]))

(deftest topic-destinations
  (testing "applies configured prefix"
    (is (= "topic://monkeyci.test.builds"
           (-> {:prefix "monkeyci.test"}
               (sut/topic-destinations)
               (get :build/pending)))))

  (testing "maps known event types"
    (let [dests (sut/topic-destinations {:prefix "monkeyci.test"})
          types [:build/triggered
                 :build/pending
                 :build/queued
                 :build/initializing
                 :build/start
                 :build/end
                 :build/canceled
                 :build/updated
                 :script/initializing
                 :script/start
                 :script/end
                 :job/pending
                 :job/initializing
                 :job/start
                 :job/end
                 :job/skipped
                 :job/executed
                 :container/pending
                 :container/job-queued
                 :container/start
                 :container/end
                 :command/start
                 :command/end
                 :sidecar/start
                 :sidecar/end]]
      (doseq [t types] 
        (is (contains? dests t)
            (str "should map " t))))))

(deftest queue-destinations
  (testing "adds queue suffix to each destination"
    (is (= "topic://monkeyci.test.builds::monkeyci.test.builds.q"
           (-> {:prefix "monkeyci.test"}
               (sut/queue-destinations)
               (get :build/pending))))))
