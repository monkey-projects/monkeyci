(ns monkey.ci.extensions-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.build.core :as bc]
            [monkey.ci
             [extensions :as sut]
             [jobs :as j]]
            [monkey.ci.helpers :as h]))

(defmacro with-extensions [& body]
  `(let [ext# @sut/registered-extensions]
     (try
       (reset! sut/registered-extensions sut/new-register)
       ~@body
       (finally
         (reset! sut/registered-extensions ext#)))))

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

(deftest wrap-job
  (let [ext {:key :test/wrapped
             :before (fn [rt]
                       (assoc rt ::before? true))
             :after  (fn [rt]
                       (assoc-in rt [:job :result ::after?] true))}
        reg (sut/register sut/new-register ext)
        wrapped (sut/wrap-job (bc/action-job "test-job"
                                             (fn [rt]
                                               (assoc bc/success
                                                      ::executed? true
                                                      ::before? (::before? rt)))
                                             {(:key ext) ::extension-config})
                              reg)
        events (h/fake-events)
        rt (-> (h/test-rt)
               (assoc :job wrapped
                      :events events))]
    (testing "creates job"
      (is (j/job? wrapped)))
    
    (testing "executes job"
      (is (true? (::executed? @(j/execute! wrapped rt)))))

    (testing "executes job without any extensions"
      (let [wrapped (sut/wrap-job (bc/action-job "regular-job" (constantly bc/success)))]
        (is (= bc/success @(j/execute! wrapped {})))))
    
    (testing "invokes `before` extension"
      (is (true? (::before? @(j/execute! wrapped rt)))))
    
    (testing "invokes `after` extension"
      (is (true? (::after? @(j/execute! wrapped rt)))))

    (testing "dispatches `job/updated` event before invoking extensions"
      (let [evt @(:recv events)]
        (is (pos? (count evt)))
        (is (= :job/updated (-> evt first :type)))))))
