(ns monkey.ci.context-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.spec.alpha :as s]
            [monkey.ci
             [context :as sut]
             [spec :as spec]]))

(deftest get-sid
  (testing "returns build sid"
    (is (= ::build-sid (sut/get-sid {:build {:sid ::build-sid}}))))

  (testing "constructs sid from account"
    (is (= [:a :b :c] (sut/get-sid {:account
                                    {:customer-id :a
                                     :project-id :b
                                     :repo-id :c}}))))

  (testing "`nil` if incomplete account"
    (is (nil? (sut/get-sid {:account {:project-id "p"
                                      :repo-id "r"}})))
    (is (nil? (sut/get-sid {:account {:customer-id "c"
                                      :repo-id "r"}})))
    (is (nil? (sut/get-sid {:account {:customer-id "c"
                                      :project-id "p"}})))))

(deftest get-step-id
  (testing "combines build id, pipeline and step"
    (is (= "test-build-test-pipeline-1"
           (-> {:build {:build-id "test-build"}
                :pipeline {:name "test-pipeline"}
                :step {:index 1}}
               (sut/get-step-id)))))
  
  (testing "uses pipeline index when no name"
    (is (= "test-build-0-1"
           (-> {:build {:build-id "test-build"}
                :pipeline {:index 0}
                :step {:index 1}}
               (sut/get-step-id))))))

(deftest script-context
  (testing "sets containers type"
    (is (= :test-type
           (-> {:monkeyci-containers-type "test-type"}
               (sut/script-context {})
               :containers
               :type))))

  (testing "sets logging config"
    (is (= :file
           (-> {:monkeyci-logging-type "file"}
               (sut/script-context {})
               :logging
               :type))))

  (testing "initializes logging maker"
    (is (fn? (-> {:monkeyci-logging-type "file"}
                 (sut/script-context {})
                 :logging
                 :maker))))

  (testing "initializes cache store"
    (is (some? (-> {:monkeyci-cache-type "disk"}
                   (sut/script-context {})
                   :cache
                   :store))))

  (testing "initializes artifacts store"
    (is (some? (-> {:monkeyci-artifacts-type "disk"}
                   (sut/script-context {})
                   :artifacts
                   :store))))

  (testing "groups api settings"
    (is (= "test-socket"
           (-> {:monkeyci-api-socket "test-socket"}
               (sut/script-context {})
               :api
               :socket))))

  (testing "matches spec"
    (is (true? (s/valid? ::spec/script-config (sut/script-context {} {})))))

  (testing "provides oci credentials from env"
    (is (= "test-fingerprint"
           (-> {:monkeyci-logging-credentials-key-fingerprint "test-fingerprint"}
               (sut/script-context {})
               :logging
               :credentials
               :key-fingerprint))))

  (testing "parses sid"
    (is (= ["a" "b" "c"]
           (-> {:monkeyci-build-sid "a/b/c"}
               (sut/script-context {})
               :build
               :sid))))

  (testing "groups git subkeys"
    (is (= "test-ref"
           (-> {:monkeyci-build-git-ref "test-ref"}
               (sut/script-context {})
               :build
               :git
               :ref)))))
