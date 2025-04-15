(ns monkey.ci.agent.main-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [monkey.ci.agent.main :as sut]
            [monkey.ci.web.http :as wh]
            [monkey.ci.test
             [config :as tc]
             [helpers :as h]]))

(deftest main
  (with-redefs [wh/on-server-close (fn [s]
                                     (.close (:server s)))]
    (h/with-tmp-dir dir
      (let [config-file (fs/path dir "config.edn")]
        (is (nil? (spit (fs/file config-file) (pr-str tc/base-config))))
        (testing "starts agent runtime"
          (is (nil? (sut/-main (str config-file)))))))))
