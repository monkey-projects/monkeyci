(ns monkey.ci.containers.promtail-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.containers.promtail :as sut]))

(deftest promtail-config
  (let [conf {:loki-url "http://loki-test"
              :token "test-token"
              :paths ["/test/dir"]
              :customer-id "test-cust"
              :repo-id "test-repo"
              :build-id "test-build"
              :job-id "test-job"}
        pc (sut/promtail-config conf)]
    
    (testing "contains default positions stanza"
      (is (string? (get-in pc [:positions :filename]))))

    (testing "adds client stanza"
      (is (= "http://loki-test"
             (-> pc :clients first :url)))
      (is (= "test-cust"
             (-> pc :clients first :tenant-id)))

      (testing "with bearer token"
        (is (= "test-token"
               (-> pc :clients first :bearer-token)))))

    (testing "adds scrape config"
      (let [sc (-> pc :scrape-configs first)]
        (is (some? sc))

        (testing "with static config per path"
          (is (= 1 (count (:static-configs sc)))))

        (testing "with build ids as labels"
          (let [keys [:customer-id :repo-id :build-id :job-id]]
            (is (= (select-keys conf keys)
                   (-> sc :static-configs first :labels (select-keys keys))))))))))

(deftest yaml-config
  (testing "generates yaml config"
    (is (.contains (sut/yaml-config {:customer-id "test-cust"})
                   "tenant_id: test-cust" ))))

(deftest promtail-container
  (testing "creates container config for promtail"
    (let [conf (sut/promtail-container {})]
      (is (map? conf))
      (is (= "docker.io/grafana/promtail:2.9.2" (:image-url conf)))
      (is (= "promtail" (:display-name conf)))))

  (testing "uses configured promtail version"
    (is (= "docker.io/grafana/promtail:123"
           (-> {:image-tag "123"}
               (sut/promtail-container)
               :image-url)))))

(deftest rt->config
  (testing "builds promtail config input map"
    (is (= {:image-url "promtail-img"
            :image-tag "promtail-version"
            :loki-url "http://loki"
            :customer-id "test-cust"
            :repo-id "test-repo"
            :build-id "test-build"
            :job-id "test-job"
            :token "test-token"}
           (sut/rt->config {:config
                            {:promtail
                             {:image-url "promtail-img"
                              :image-tag "promtail-version"
                              :loki-url "http://loki"}
                             :api
                             {:token "test-token"}}
                            :build
                            {:sid ["test-cust" "test-repo" "test-build"]}
                            :job
                            {:id "test-job"}})))))
