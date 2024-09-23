(ns monkey.ci.spec.events-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [monkey.ci.spec.events :as sut]
            [monkey.ci.time :as t]
            [monkey.ci.helpers :as h]))

(deftest event-spec
  (testing "validates `:build/initializing` event"
    (is (not (s/valid? ::sut/event
                       {:type :build/initializing
                        :time (t/now)
                        :sid (h/gen-build-sid)})))
    (is (s/valid? ::sut/event
                  {:type :build/initializing
                   :time (t/now)
                   :sid (h/gen-build-sid)
                   :build {:build-id "test-build"}})))

  (testing "validates `:build/start` event"
    (is (not (s/valid? ::sut/event
                       {:type :build/start
                        :time (t/now)
                        :sid (h/gen-build-sid)})))
    (is (s/valid? ::sut/event
                  {:type :build/start
                   :time (t/now)
                   :sid (h/gen-build-sid)
                   :credit-multiplier 2})))

  (testing "validates `:build/end` event"
    (is (not (s/valid? ::sut/event
                       {:type :build/end
                        :time (t/now)
                        :sid (h/gen-build-sid)})))
    (is (s/valid? ::sut/event
                  {:type :build/end
                   :time (t/now)
                   :sid (h/gen-build-sid)
                   :status :success})))

  (testing "validates `:script/initializing` event"
    (is (not (s/valid? ::sut/event
                       {:type :script/initializing
                        :time (t/now)
                        :sid (h/gen-build-sid)})))
    (is (s/valid? ::sut/event
                  {:type :script/initializing
                   :time (t/now)
                   :sid (h/gen-build-sid)
                   :script-dir "test/dir"})))

  (testing "validates `:script/start` event"
    (is (not (s/valid? ::sut/event
                       {:type :script/start
                        :time (t/now)
                        :sid (h/gen-build-sid)})))
    (is (s/valid? ::sut/event
                  {:type :script/start
                   :time (t/now)
                   :sid (h/gen-build-sid)
                   :jobs [{:id "test-job"
                           :type :container}]})))

  (testing "validates `:script/end` event"
    (is (not (s/valid? ::sut/event
                       {:type :script/end
                        :time (t/now)
                        :sid (h/gen-build-sid)})))
    (is (s/valid? ::sut/event
                  {:type :script/end
                   :time (t/now)
                   :sid (h/gen-build-sid)
                   :status :success})))

  (testing "validates `:job/initializing` event"
    (is (not (s/valid? ::sut/event
                       {:type :job/initializing
                        :time (t/now)
                        :sid (h/gen-build-sid)
                        :job-id "test-job"})))
    (is (s/valid? ::sut/event
                  {:type :job/initializing
                   :time (t/now)
                   :sid (h/gen-build-sid)
                   :job-id "test-job"
                   :job {:id "test-job"
                         :type :container}})))
  
  (testing "validates `:job/start` event"
    (is (not (s/valid? ::sut/event
                       {:type :job/start
                        :time (t/now)
                        :sid (h/gen-build-sid)})))
    (is (s/valid? ::sut/event
                  {:type :job/start
                   :time (t/now)
                   :sid (h/gen-build-sid)
                   :job-id "test-job"})))

  (testing "validates `:job/end` event"
    (is (not (s/valid? ::sut/event
                       {:type :job/end
                        :time (t/now)
                        :sid (h/gen-build-sid)})))
    (is (s/valid? ::sut/event
                  {:type :job/end
                   :time (t/now)
                   :sid (h/gen-build-sid)
                   :job-id "test-job"
                   :status :success}))))
