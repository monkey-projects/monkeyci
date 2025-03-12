(ns monkey.ci.metrics.events-test
  (:require [clojure.string :as cs]
            [clojure.test :refer [deftest testing is]]
            [monkey.ci.metrics.events :as sut]
            [monkey.ci.prometheus :as prom]
            [monkey.mailman.core :as mmc]))

(deftest evt-counter
  (testing "increases counter on enter"
    (let [reg (prom/make-registry)
          counter (prom/make-counter "test_counter" reg {:labels ["test_lbl"]})
          {:keys [enter] :as i} (sut/evt-counter counter (juxt :key))
          ctx {:key "test-val"}]
      (is (= 0.0 (prom/counter-get counter ["test-val"])))
      (is (= ctx (enter ctx)))
      (is (= 1.0 (prom/counter-get counter ["test-val"]))))))

(deftest routes
  (let [reg (prom/make-registry)
        router (-> (sut/make-routes reg)
                   (mmc/router))
        types [:build/triggered
               :build/queued
               :build/start
               :build/end]]
    (doseq [t types]
      (let [id (str (namespace t) "_" (name t))]
        (testing (format "`%s` increases counter for %s" (str t) id)
          (is (nil? (-> {:type t
                         :sid ["test-cust"]}
                        (router)
                        :result)))
          (is (cs/includes? (prom/scrape reg)
                            (format "monkeyci_%s_total{customer=\"test-cust\"} 1.0" id))))))))
