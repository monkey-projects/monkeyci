(ns monkey.ci.containers.k8s-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [medley.core :as mc]
            [monkey.ci.containers.k8s :as sut]))

(deftest prepare-pod-config
  (let [actions (sut/prepare-pod-config {:ns "builds"
                                         :build {:build-id "test-build"}
                                         :job {:id "test-job"
                                               :type :container
                                               :image "job-img"
                                               :script ["test" "script"]}
                                         :sidecar
                                         {:image-url "test-image"
                                          :image-tag "test-tag"}})]
    (is (sequential? actions))

    (let [pc (first actions)]
      (testing "creates job creation request"
        (is (= :Job (:kind pc)))
        (is (= :create (:action pc))))

      (let [req (:request pc)]
        (is (= "batch/v1" (-> req :body :api-version)))
        
        (testing "uses configured namespace"
          (is (= "builds" (:namespace req))))

        (testing "has build and job name"
          (let [md (get-in req [:body :metadata])]
            (is (= "test-build-test-job" (:name md)))))

        (let [co (get-in req [:body :spec :template :spec :containers])
              co-by-name (->> co
                              (group-by :name)
                              (mc/map-vals first))]
          (testing "has job, sidecar and promtail containers"
            (is (= 3 (count co)))
            (is (= #{"job" "sidecar" "promtail"}
                   (set (map :name co)))))

          (testing "promtail container"
            (let [c (get co-by-name "promtail")]
              (is (some? c))

              (testing "uses promtail image"
                (is (cs/starts-with? (:image c) "docker.io/grafana/promtail")))

              (testing "mounts"
                (let [vm (:volume-mounts c)]
                  (is (= 2 (count vm)))
                  
                  (testing "has config mount"
                    (is (contains? (set (map :name vm)) "promtail-config")))

                  (testing "has checkout mount"
                    (is (contains? (set (map :name vm)) "checkout")))))

              (testing "has resources"
                (is (some? (:resources c))))))

          (testing "sidecar container"
            (let [c (get co-by-name "sidecar")]
              (is (some? c))

              (testing "runs sidecar"
                (is (= "test-image:test-tag" (:image c)))
                (is (contains? (set (:command c)) "sidecar")))

              (testing "mounts"
                (let [vm (:volume-mounts c)]
                  (is (= 2 (count vm)))
                  
                  (testing "has config mount"
                    (is (contains? (set (map :name vm)) "config")))
                  
                  (testing "has checkout mount"
                    (is (contains? (set (map :name vm)) "checkout")))))))

          (testing "job container"
            (let [c (get co-by-name "job")]
              (is (some? c))

              (testing "runs job image"
                (is (= "job-img" (:image c))))

              (testing "mounts"
                (let [vm (:volume-mounts c)]
                  (is (= 2 (count vm)))
                  
                  (testing "has script mount"
                    (is (contains? (set (map :name vm)) "scripts")))

                  (testing "has checkout mount"
                    (is (contains? (set (map :name vm)) "checkout"))))))))))))
