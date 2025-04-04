(ns monkey.ci.extensions-test
  (:require [clojure.test :refer [deftest is testing]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.extensions :as sut]
            [monkey.ci.test.extensions :refer [with-extensions]]))

(deftest register!
  (testing "adds to registered extensions"
    (with-extensions
      (let [ext {:key :test/ext
                 :before identity}]
        (is (empty? (reset! sut/registered-extensions {})))
        (is (some? (sut/register! ext)))
        (is (= {:test/ext ext} @sut/registered-extensions))))))

(deftest apply-extensions-before
  (testing "executes `before` fn of registered extension for key"
    (let [ext {:key :test/before
               :before (fn [rt]
                         ;; Get the config from the job
                         (assoc rt ::value (get-in rt [:job :test/before])))}
          reg (sut/register sut/new-register ext)
          job (bc/action-job "test-job" (constantly bc/success)
                             {:test/before "config for extensions"})
          res (sut/apply-extensions-before {:job job} reg)]
      (is (= "config for extensions"
             (::value res)))))

  (testing "executes `before-job` multimethod for key"
    (with-extensions
      ;; Register a test extension
      (defmethod sut/before-job :test/before [_ rt]
        (assoc rt ::value (get-in rt [:job :test/before])))
      (is (some? (get-method sut/before-job :test/before)))
      
      (let [job (bc/action-job "test-job" (constantly bc/success)
                               {:test/before "config for extensions"})
            res (sut/apply-extensions-before {:job job})]
        (is (= "config for extensions"
               (::value res)))
        (remove-method sut/before-job :test/before)))))

(deftest apply-extensions-after
  (testing "executes `after` fn of registered extension for key"
    (let [ext {:key :test/after
               :after (fn [rt]
                         ;; Get the config from the job
                         (assoc rt ::value (get-in rt [:job :test/after])))}
          reg (sut/register sut/new-register ext)
          job (bc/action-job "test-job" (constantly bc/success)
                             {:test/after "config for extensions"})
          res (sut/apply-extensions-after {:job job} reg)]
      (is (= "config for extensions"
             (::value res)))))

  (testing "executes `after-job` multimethod for key"
    (with-extensions
      ;; Register a test extension
      (defmethod sut/after-job :test/after [_ rt]
        (assoc rt ::value (get-in rt [:job :test/after])))
      (is (some? (get-method sut/after-job :test/after)))
      
      (let [job (bc/action-job "test-job" (constantly bc/success)
                               {:test/after "config for extensions"})
            res (sut/apply-extensions-after {:job job})]
        (is (= "config for extensions"
               (::value res)))
        (remove-method sut/after-job :test/after)))))

(deftest before-interceptor
  (let [{:keys [enter] :as i} sut/before-interceptor
        ext {:key :test/wrapped
             :before (fn [ctx]
                       (assoc ctx ::before? true))
             :after  (fn [ctx]
                       (assoc-in ctx [:job :result ::after?] true))}]
    
    (is (keyword? (:name i)))
    
    (testing "`enter` applies interceptors to job context"
      (with-extensions
        (let [job {:id "test-job"
                   :test/wrapped {}}]
          (is (some? (sut/register! ext)))
          (is (= {::before? true
                  :job job}
                 (-> {}
                     (emi/set-job-ctx {:job job})
                     (enter)
                     (emi/get-job-ctx)))))))))

(deftest after-interceptor
  (let [{:keys [enter] :as i} sut/after-interceptor
        ext {:key :test/wrapped
             :before (fn [ctx]
                       (assoc ctx ::before? true))
             :after  (fn [ctx]
                       (assoc-in ctx [:job :result ::after?] true))}]
    (is (keyword? (:name i)))

    (testing "`enter` applies interceptors to job result"
      (with-extensions
        (let [job {:id "test-job"
                   :test/wrapped {}
                   :result {}}]
          (is (some? (sut/register! ext)))
          (is (= {::after? true}
                 (-> {:event
                      {:status :success
                       :result {}}}
                     (emi/set-job-ctx {:job job})
                     (enter)
                     (emi/get-job-ctx)
                     :job
                     :result))))))))
