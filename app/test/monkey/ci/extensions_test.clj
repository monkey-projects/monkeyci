(ns monkey.ci.extensions-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.extensions :as sut]))

(defmacro with-extensions [& body]
  `(let [ext# @sut/registered-extensions]
     (try
       (reset! sut/registered-extensions {})
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
          reg (sut/register {} ext)
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
          reg (sut/register {} ext)
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

