(ns monkey.ci.integration-test.job-test
  "Integration test that runs a build api, sidecar and script, similar to what would
   happen when running a container job."
  (:require
   [clojure.test :refer [deftest is testing]]
   [monkey.ci.build :as b]
   [monkey.ci.cuid :as cuid]
   [monkey.ci.test.helpers :as h]))

(deftest container-job-simulation
  (testing "runs sidecar and script commands"
    (let [build (zipmap b/sid-props (repeatedly cuid/random-cuid))])))
