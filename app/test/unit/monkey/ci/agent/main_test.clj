(ns monkey.ci.agent.main-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [monkey.ci.agent.main :as sut]
            [monkey.ci.utils :as u]
            [monkey.ci.web.http :as wh]
            [monkey.ci.test
             [config :as tc]
             [helpers :as h]]))

(deftest main
  (with-redefs [wh/on-server-close (fn [s]
                                     (future (.close (:server s))))
                u/add-shutdown-hook! (constantly nil)]
    (h/with-tmp-dir dir
      (let [config-file (fs/path dir "config.edn")]
        (is (nil? (spit (fs/file config-file) (-> tc/base-config
                                                  (assoc :poll-loop {:type :manifold})
                                                  (pr-str)))))
        (testing "starts agent runtime"
          (is (nil? (sut/-main (str config-file)))))))))
