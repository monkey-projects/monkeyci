(ns monkey.ci.process-test
  (:require
   [aleph.http :as http]
   [babashka.fs :as fs]
   [babashka.process :as bp]
   [clj-commons.byte-streams :as bs]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as cs]
   [clojure.test :refer :all]
   [manifold.deferred :as md]
   [monkey.ci.build.core :as bc]
   [monkey.ci.containers]
   [monkey.ci.logging :as l]
   [monkey.ci.process :as sut]
   [monkey.ci.protocols :as p]
   [monkey.ci.script :as script]
   [monkey.ci.script.config :as sco]
   [monkey.ci.sid :as sid]
   [monkey.ci.spec.common :as sc]
   [monkey.ci.spec.events :as se]
   [monkey.ci.spec.script :as ss]
   [monkey.ci.test.aleph-test :as at]
   [monkey.ci.test.helpers :as h]
   [monkey.ci.test.runtime :as trt]
   [monkey.ci.utils :as u]))

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
