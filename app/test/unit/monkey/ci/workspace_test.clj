(ns monkey.ci.workspace-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.workspace :as sut]
            [monkey.ci.helpers :as h]))

(deftest restore
  (testing "nothing if no workspace in build"
    (let [rt {}]
      (is (= rt (sut/restore rt)))))

  (testing "restores using the workspace path in build into checkout dir"
    (let [stored (atom {"path/to/workspace" "local"})
          store (h/strict-fake-blob-store stored)
          rt {:build {:workspace "path/to/workspace"
                      :checkout-dir "local/dir"}
              :workspace store}]
      (is (true? (-> (sut/restore rt)
                     (deref)
                     (get-in [:build :workspace/restored?]))))
      (is (empty? @stored)))))

