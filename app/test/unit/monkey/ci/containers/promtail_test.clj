(ns monkey.ci.containers.promtail-test
  (:require [clojure.test :refer [deftest testing is]]
            [monkey.ci.containers.promtail :as sut]
            [monkey.ci
             [build :as b]
             [cuid :as cuid]]))

(deftest promtail-config
  (let [conf {:loki-url "http://loki-test"
              :token "test-token"
              :paths ["/test/dir"]
              :org-id "test-cust"
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
          (let [keys [:org-id :repo-id :build-id :job-id]]
            (is (= (select-keys conf keys)
                   (-> sc :static-configs first :labels (select-keys keys))))))))))

(deftest yaml-config
  (testing "generates yaml config"
    (is (.contains (sut/yaml-config {:org-id "test-cust"})
                   "tenant_id: test-cust" ))))

(deftest promtail-container
  (testing "creates container config for promtail"
    (let [conf (sut/promtail-container {})]
      (is (map? conf))
      (is (re-matches #"^docker.io/grafana/promtail:.+$" (:image-url conf)))
      (is (= "promtail" (:display-name conf)))))

  (testing "uses configured promtail version"
    (is (= "docker.io/grafana/promtail:123"
           (-> {:image-tag "123"}
               (sut/promtail-container)
               :image-url)))))

(deftest make-config
  (testing "builds promtail config input map"
    (is (= {:image-url "promtail-img"
            :image-tag "promtail-version"
            :loki-url "http://loki"
            :org-id "test-cust"
            :repo-id "test-repo"
            :build-id "test-build"
            :job-id "test-job"
            :token "test-token"}
           (sut/make-config
            {:image-url "promtail-img"
             :image-tag "promtail-version"
             :loki-url "http://loki"
             :token "test-token"}
            {:id "test-job"}
            ["test-cust" "test-repo" "test-build"]))))

  (testing "makes id props from build sid"
    (let [sid (repeatedly 3 cuid/random-cuid)]
      (is (= (zipmap b/sid-props sid)
             (-> (sut/make-config {} {} sid)
                 (select-keys b/sid-props)))))))

