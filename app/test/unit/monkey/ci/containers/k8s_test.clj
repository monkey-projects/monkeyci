(ns monkey.ci.containers.k8s-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as cs]
            [kubernetes-api.core :as k8s-api]
            [monkey.mailman.core :as mmc]
            [medley.core :as mc]
            [monkey.ci.containers.k8s :as sut]))

(deftest prepare-pod-config
  (let [actions (sut/prepare-pod-config {:ns "builds"
                                         :build {:build-id "test-build"
                                                 :checkout-dir "checkout"}
                                         :job {:id "test-job"
                                               :type :container
                                               :image "job-img"
                                               :script ["test" "script"]
                                               :container/env {"test_var" "test val"}
                                               :memory 2
                                               :cpus 1
                                               :arch :arm}
                                         :sidecar
                                         {:image-url "test-image"
                                          :image-tag "test-tag"
                                          :log-config "test-log"}})]
    (is (sequential? actions))

    (let [pc (->> actions
                  (filter (comp (partial = :Job) :kind))
                  (first))]
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
                    (is (contains? (set (map :name vm)) "checkout")))))

              (testing "env vars"
                (letfn [(find-var [n]
                          (->> (:env c)
                               (filter (comp (partial = n) :name))
                               (first)))]
                  (testing "contains configured on job"
                    (is (= {:name "test_var"
                            :value "test val"}
                           (find-var "test_var"))))

                  (testing "contains additional"
                    (is (string? (:value (find-var "MONKEYCI_WORK_DIR"))))
                    (is (string? (:value (find-var "MONKEYCI_SCRIPT_DIR"))))
                    (is (string? (:value (find-var "MONKEYCI_EVENT_FILE")))))))

              (testing "requests job resources"
                (is (= "2G" (get-in c [:resources :requests :memory])))
                (is (= 1 (get-in c [:resources :requests :cpu]))))))

          (testing "selects node based on arch"
            (is (= "arm64"
                   (-> req
                       :body
                       :spec
                       :template
                       :spec
                       :node-selector
                       (get "kubernetes.io/arch"))))))))

    (testing "configmaps"
      (letfn [(find-cm [n]
                (->> actions
                     (filter (every-pred (comp (partial = :ConfigMap) :kind)
                                         (comp (partial = n) :name :metadata :body :request)))
                     (first)))]
        (testing "job config"
          (let [c (find-cm "job-config")
                cc (get-in c [:request :body :data])]
            (is (some? c))

            (testing "contains shell script"
              (is (some? (get cc "job.sh"))))

            (testing "contains script per line"
              (is (= "test" (get cc "0")))
              (is (= "script" (get cc "1"))))))

        (testing "promtail config"
          (let [c (find-cm "promtail-config")
                cc (get-in c [:request :body :data])]
            (is (some? c))

            (testing "contains config yaml"
              (is (some? (get cc "config.yml"))))))

        (testing "sidecar config"
          (let [c (find-cm "sidecar-config")
                cc (get-in c [:request :body :data])]
            (is (some? c))

            (testing "contains logback config"
              (is (some? (get cc "logback.xml"))))

            (testing "contains config edn"
              (is (some? (get cc "config.edn"))))))))))

(deftest run-k8s-actions
  (let [client ::test-client
        {:keys [leave] :as i} (sut/run-k8s-actions client)
        action {:kind :TestAction
                :action :create}]
    (is (keyword? (:name i)))
    
    (testing "`leave` invokes action on client"
      (with-redefs [k8s-api/invoke (fn [client action]
                                     {:client client
                                      :action action})]
        (is (= [{:client client
                 :action action}]
               (-> {}
                   (sut/set-k8s-actions [action])
                   (leave)
                   (sut/get-k8s-results))))))))

(deftest job-queued
  (let [build {:build-id "test-build"}
        job {:id "test-job"}
        res (-> {:event
                 {:type :k8s/job-queued
                  :job job}}
                (sut/set-build build)
                (sut/job-queued))]
    (testing "sets k8s actions to create job"
      (is (not-empty (sut/get-k8s-actions res))))))

(deftest make-routes
  (let [test-conf {:k8s {:client ::test-client}
                   :build {:build-id "test-build"}}]
    (testing "handles required events"
      (let [r (sut/make-routes test-conf)
            exp [:k8s/job-queued
                 :container/start
                 :container/end
                 :sidecar/end
                 :job/executed]]
        (doseq [t exp]
          (is (contains? (set (map first r)) t)
              (str "Should handle event " t)))))

    (testing "`k8s/job-queued`"
      (let [k8s-actions (atom [])
            fake-k8s {:name ::sut/run-k8s-actions
                      :leave (fn [ctx]
                               (reset! k8s-actions (sut/get-k8s-actions ctx))
                               ctx)}
            r (-> (sut/make-routes test-conf)
                  (mmc/router)
                  (mmc/replace-interceptors [fake-k8s]))
            res (r {:type :k8s/job-queued
                    :job {}})]
        
        (testing "creates kubernetes job"
          (is (not-empty @k8s-actions)))))))
