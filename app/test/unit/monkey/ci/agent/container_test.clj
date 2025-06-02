(ns monkey.ci.agent.container-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [monkey.ci.agent.container :as sut]
            [monkey.ci.runtime.common :as rc]
            [monkey.ci.utils :as u]
            [monkey.ci.test
             [config :as tc]
             [helpers :as h]]))

(deftest main
  (let [sys (atom nil)]
    (with-redefs [rc/with-system (fn [s _]
                                   (reset! sys s)
                                   nil)]
      (h/with-tmp-dir dir
        (let [config-file (fs/path dir "config.edn")]
          (is (nil? (spit (fs/file config-file) (-> tc/base-config
                                                    (assoc :poll-loop {:type :manifold})
                                                    (pr-str)))))
          (testing "starts agent runtime"
            (is (nil? (sut/-main (str config-file))))
            (is (some? @sys))))))))
