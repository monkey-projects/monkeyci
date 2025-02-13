(ns monkey.ci.events.mailman.bridge-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.stuartsierra.component :as co]
            [monkey.ci.events.mailman.bridge :as sut]
            [monkey.mailman
             [core :as mmc]
             [mem :as mmm]]))

(deftest bridge-routes
  (testing "routes required event types"
    (let [events [:build/pending :build/initializing :build/start :build/end :build/canceled
                  :script/initializing :script/start :script/end
                  :job/initializing :job/start :job/executed :job/end :job/skipped
                  :container/start :container/end
                  :command/start :command/end]
          routes (into {} sut/bridge-routes)]
      (doseq [e events]
        (is (= 1 (count (get routes e))))))))
