(ns monkey.ci.events.mailman.jms-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.events.mailman.jms :as sut]))

(deftest event-destinations
  (testing "applies configured prefix"
    (is (= "queue://monkeyci.test.builds"
           (-> {:prefix "monkeyci.test"}
               (sut/event-destinations)
               (get :build/pending)))))

  (testing "maps known event types"
    (let [dests (sut/event-destinations {:prefix "monkeyci.test"})
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
                 :container/start
                 :container/end
                 :command/start
                 :command/end
                 :sidecar/start
                 :sidecar/end]]
      (doseq [t types] 
        (is (contains? dests t)
            (str "should map " t))))))
