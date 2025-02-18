(ns monkey.ci.runners.local-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.runners.local :as sut]))

(deftest make-routes
  (let [types [:build/pending
               :build/initializing
               :build/start
               :build/end]
        routes (->> (sut/make-routes {})
                    (into {}))]
    (doseq [t types]
      (testing (format "handles `%s` event type" t)
        (is (contains? routes t))))))

(deftest build-pending
  (testing "returns `build/initializing` event"
    (let [build {:status :pending}
          r (sut/build-pending {:event
                                {:type :build/pending
                                 :build build}})]
      (is (= :build/initializing (:type r)))
      (is (= :initializing (-> r :build :status))))))
