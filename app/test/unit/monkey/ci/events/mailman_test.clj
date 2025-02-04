(ns monkey.ci.events.mailman-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as spec]
            [monkey.ci.events.mailman :as sut]
            [monkey.ci
             [cuid :as cuid]
             [storage :as st]]
            [monkey.ci.spec
             [events :as se]
             [entities :as sen]]
            [monkey.ci.helpers :as h]
            [monkey.ci.test.runtime :as trt]))

(defn- validate-spec [spec obj]
  (is (spec/valid? spec obj)
      (spec/explain-str spec obj)))

(deftest use-db
  (testing "adds db to context"
    (let [i (sut/use-db ::test-db)]
      (is (= ::sut/use-db (:name i)))
      (is (fn? (:enter i)))
      (is (= ::test-db (-> ((:enter i) {})
                           ::sut/db))))))

(deftest customer-credits
  (h/with-memory-store s
    (let [cust (h/gen-cust)]
      (is (some? (st/save-customer s cust)))
      (is (some? (st/save-customer-credit s {:customer-id (:id cust)
                                             :amount 100})))
      
      (testing "adds available customer credits to context"
        (let [{:keys [enter] :as i} sut/customer-credits]
          (is (= ::sut/customer-credits (:name i)))
          (is (fn? enter))
          (is (= 100M
                 (-> {:event {:build
                              {:customer-id (:id cust)}}}
                     (sut/set-db s)
                     (enter)
                     (sut/get-credits)))))))))

(deftest save-build
  (h/with-memory-store s
    (testing "`leave` saves build from result event"
      (let [{:keys [leave] :as i} sut/save-build
            build (h/gen-build)
            get-sid (apply juxt st/build-sid-keys)]
        (is (keyword? (:name i)))
        (is (= build (-> {:result {:build build}}
                         (sut/set-db s)
                         (leave)
                         (sut/get-build))))
        (is (= build (st/find-build s (get-sid build))))))))

(deftest load-build
  (h/with-memory-store s
    (let [sid-keys [:customer-id :repo-id :build-id]
          sid (repeatedly 3 cuid/random-cuid)
          build (-> (h/gen-build)
                    (merge (zipmap sid-keys sid)))
          {:keys [enter] :as i} sut/load-build]
      (is (some? (st/save-build s build)))
      (is (some? (:name i)))
      
      (testing "`enter` reads build in context using sid"
        (is (= build (-> {:event {:sid sid}}
                         (sut/set-db s)
                         (enter)
                         (sut/get-build))))))))

(deftest build-event
  (testing "converts build into event"
    (let [{:keys [leave] :as i} (sut/build-event :build/pending)
          build (h/gen-build)
          res (-> {}
                  (sut/set-build build)
                  (leave))]
      (validate-spec ::se/event (first (:result res))))))

(deftest add-time
  (let [{:keys [leave] :as i} sut/add-time]
    (is (keyword? (:name i)))
    
    (testing "sets event times"
      (is (number? (-> {:result [{:type ::test-event}]}
                       (leave)
                       :result
                       first
                       :time))))))

(deftest check-credits
  (let [build (h/gen-build)
        ctx {:event {:type :build/triggered
                     :build build}}]
    
    (testing "returns `build/pending` event if credits available"
      (let [res (-> ctx
                    (sut/set-credits 100M)
                    (sut/check-credits))]
        (validate-spec :entity/build (:build res))
        (is (= :pending (get-in res [:build :status])))
        (is (= :build/pending (:type res)))))

    (testing "returns `build/failed` event if no credits available"
      (is (= :build/failed (-> (sut/check-credits ctx)
                               :type))))))

(deftest router
  (let [{st :storage :as rt} (trt/test-runtime)
        router (sut/make-router rt)]
    
    (testing "`build/triggered`"
      (testing "with available credits"
        (let [cust (h/gen-cust)
              creds {:id (cuid/random-cuid)
                     :customer-id (:id cust)
                     :amount 100M}]
          (is (some? (st/save-customer st cust)))
          (is (some? (st/save-customer-credit st creds)))
          
          (let [res (-> {:type :build/triggered
                         :build (-> (h/gen-build)
                                    (assoc :customer-id (:id cust)))}
                        (router)
                        first
                        :result)]
            (testing "results in `build/pending` event"
              (is (= :build/pending
                     (-> res first :type))))

            (testing "saves build in db"
              (is (some? (st/find-build st (-> res first :sid))))))))

      (testing "when no credits"
        (let [cust (h/gen-cust)]
          (is (some? (st/save-customer st cust)))

          (let [res (-> {:type :build/triggered
                         :build (-> (h/gen-build)
                                    (assoc :customer-id (:id cust)))}
                        (router)
                        first
                        :result)]
            
            (testing "results in `build/failed` event"
              (is (= :build/failed (-> res first :type))))

            (testing "saves build in db"
              (is (some? (st/find-build st (-> res first :sid)))))))))))
