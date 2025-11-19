(ns monkey.ci.metrics.events-test
  (:require [clojure.string :as cs]
            [clojure.test :refer [deftest testing is]]
            [monkey.ci.metrics
             [events :as sut]
             [prometheus :as prom]]
            [monkey.mailman.core :as mmc]))

(deftest evt-counter
  (testing "increases counter on enter"
    (let [reg (prom/make-registry)
          counter (prom/make-counter "test_counter" reg {:labels ["test_lbl"]})
          {:keys [enter] :as i} (sut/evt-counter counter (juxt :key))
          ctx {:key "test-val"}]
      (is (= 0.0 (prom/counter-get counter ["test-val"])))
      (is (= counter (sut/get-counter (enter ctx))))
      (is (= 1.0 (prom/counter-get counter ["test-val"]))))))

(deftest routes
  (let [reg (prom/make-registry)
        router (-> (sut/make-routes reg)
                   (mmc/router))
        type->id #(str (namespace %) "_" (name %))]
    (testing "org events"
      (let [types [:build/triggered
                   :build/queued
                   :build/start
                   :build/canceled
                   :script/start
                   :job/queued
                   :job/start]]
        (doseq [t types]
          (let [id (type->id t)]
            (testing (format "`%s` increases counter for %s with org label" (str t) id)
              (is (nil? (-> {:type t
                             :sid ["test-org"]}
                            (router)
                            :result)))
              (is (cs/includes?
                   (prom/scrape reg)
                   (format "monkeyci_%s_total{org=\"test-org\"} 1.0" id))))))))

    (testing "status events"
      (let [types [:build/end
                   :script/end
                   :job/executed
                   :job/end]]
        (doseq [t types]
          (let [id (type->id t)]
            (testing (format "`%s` increases counter for %s with status label" (str t) id)
              (is (nil? (-> {:type t
                             :sid ["test-org"]
                             :status :success}
                            (router)
                            :result)))
              (is (cs/includes?
                   (prom/scrape reg)
                   (format "monkeyci_%s_total{org=\"test-org\",status=\"success\"} 1.0" id))))))))))
