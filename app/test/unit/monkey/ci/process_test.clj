(ns monkey.ci.process-test
  (:require [aleph.http :as http]
            [babashka
             [fs :as fs]
             [process :as bp]]
            [clj-commons.byte-streams :as bs]
            [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.spec.alpha :as s]
            [manifold.deferred :as md]
            [monkey.ci
             [logging :as l]
             [containers]
             [process :as sut]
             [protocols :as p]
             [script :as script]
             [sid :as sid]
             [utils :as u]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.script.config :as sco]
            [monkey.ci.spec
             [common :as sc]
             [events :as se]
             [script :as ss]]
            [monkey.ci.helpers :as h]
            [monkey.ci.test
             [aleph-test :as at]
             [runtime :as trt]]))

(deftest test!
  (let [build {:build-id "test-build"}
        rt (trt/test-runtime)]
    
    (with-redefs [bp/process identity]
      (testing "runs child process to execute unit tests"
        (is (= "clojure" (-> (sut/test! build rt)
                             :cmd
                             first))))

      (testing "enables watch mode if passed in cmdline"
        (is (cs/includes? (-> (sut/test! build (assoc-in rt [:config :args :watch] true))
                              :cmd
                              (nth 2))
                          ":watch? true"))))))

(deftest generate-test-deps
  (testing "includes monkeyci test lib"
    (is (contains? (-> (sut/generate-test-deps false false)
                       :aliases
                       :monkeyci/test
                       :extra-deps)
                   'com.monkeyci/test)))

  (testing "points to local test lib dir in dev mode"
    (is (= (-> (u/cwd) (fs/parent) (fs/path "test-lib") str)
           (-> (sut/generate-test-deps true
                                       false)
               :aliases
               :monkeyci/test
               :extra-deps
               (get 'com.monkeyci/test)
               :local/root))))

  (testing "enables watching if specified"
    (is (true? (-> (sut/generate-test-deps false true)
                   :aliases
                   :monkeyci/test
                   :exec-args
                   :watch?)))))
