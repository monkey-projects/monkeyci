(ns instances
  (:require [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [config :as co]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [config :as config]
             [containers :as c]
             [oci :as oci]
             [runtime :as rt]]
            [monkey.ci.containers.oci :as oci-cont]
            [monkey.ci.runners.oci :as ro]
            [monkey.oci.container-instance.core :as ci]
            [manifold
             [deferred :as md]
             [time :as mt]]))

(def busybox-tests
  [{:display-name "busybox-amd"
    :image-url "docker.io/busybox:latest"
    :command ["/bin/sh"]
    :arguments ["-c" "echo ok"]
    :shape "CI.Standard.E4.Flex"}
   {:display-name "busybox-arm"
    :image-url "docker.io/busybox:latest"
    :command ["/bin/sh"]
    :arguments ["-c" "echo ok"]}])

(def kaniko-tests
  "Configuration for several kaniko containers.  Seems they all fail if you
   don't run the executor.  Only debug images have /bin/sh.  Exit code varies
   betwen ARM and AMD: former returns 128, latter returns 1."
  [;; Exit 1
   #_{:display-name "kaniko-debug-amd"
      :image-url "gcr.io/kaniko-project/executor:v1.21.0-debug"
      :command ["/bin/sh"]
      :arguments ["-c" "echo ok"]
      :shape "CI.Standard.E4.Flex"}
   ;; Exit 128
   #_{:display-name "kaniko-debug-arm"
    :image-url "gcr.io/kaniko-project/executor:v1.21.0-debug"
    :command ["/bin/sh"]
    :arguments ["-c" "echo ok"]}
   ;; Exit 128
   #_{:display-name "kaniko-debug-while"
    :image-url "gcr.io/kaniko-project/executor:v1.21.0-debug"
    :command ["/bin/sh"]
    :arguments ["-c" "while :; do echo Running...; sleep 1; done"]}
   ;; Exit 0
   #_{:display-name "busybox-while"
    :image-url "docker.io/busybox"
    :command ["/bin/sh"]
    :arguments ["-c" "while :; do echo Running...; sleep 1; done"]}
   ;; Exit 0 (actually 137 cause it keeps running)
   #_{:display-name "busybox-musl-while"
    :image-url "docker.io/busybox:musl"
    :command ["/bin/sh"]
    :arguments ["-c" "while :; do echo Running...; sleep 1; done"]}
   ;; Exit 1: permission denied
   #_{:display-name "kaniko-debug-while-amd"
    :image-url "gcr.io/kaniko-project/executor:v1.21.0-debug"
    :command ["/busybox/sh"]
    :arguments ["-c" "while :; do echo Running...; sleep 1; done"]
    :shape "CI.Standard.E4.Flex"}
   ;; Exit 1: permission denied
   #_{:display-name "kaniko-debug-while-root"
    :image-url "gcr.io/kaniko-project/executor:v1.21.0-debug"
    :command ["/busybox/sh"]
    :arguments ["-c" "while :; do echo Running...; sleep 1; done"]
    :shape "CI.Standard.E4.Flex"
    :security-context {:security-context-type "LINUX"
                       :run-as-user 0}}
   {:display-name "monkeyci-kaniko-arm"
    :image-url "fra.ocir.io/frjdhmocn5qi/monkeyci-kaniko:latest"
    :arguments ["-c" "while :; do echo Running...; sleep 1; done"]}
   {:display-name "monkeyci-kaniko-amd"
    :image-url "fra.ocir.io/frjdhmocn5qi/monkeyci-kaniko:latest"
    :arguments ["-c" "while :; do echo Running...; sleep 1; done"]
    :shape "CI.Standard.E4.Flex"}
   ;; Exit 0
   #_{:display-name "kaniko-debug-arm-executor"
      :image-url "gcr.io/kaniko-project/executor:v1.21.0-debug"
      :arguments ["-h"]}
   ;; Exit 0
   #_{:display-name "kaniko-debug-arm-executor-path"
      :image-url "gcr.io/kaniko-project/executor:v1.21.0-debug"
      :command ["/kaniko/executor" "-h"]}
   ;; Exit 1
   #_{:display-name "kaniko-debug-arm-args"
    :image-url "gcr.io/kaniko-project/executor:v1.21.0-debug"
    :arguments ["-c" "echo ok"]}
   ;; Exit 128
   #_{:display-name "kaniko-debug-arm-busybox-args"
    :image-url "gcr.io/kaniko-project/executor:v1.21.0-debug"
    :command ["/busybox/sh"]
    :arguments ["-h"]}
   ;; Exit 0
   #_{:display-name "kaniko-amd"
      :image-url "gcr.io/kaniko-project/executor:v1.21.0"
      :arguments ["-h"]
      :shape "CI.Standard.E4.Flex"}
   ;; Exit 0
   #_{:display-name "kaniko-arm"
      :image-url "gcr.io/kaniko-project/executor:v1.21.0"
      :arguments ["-h"]}
   ;; Exit 128
   #_{:image-url "fra.ocir.io/frjdhmocn5qi/monkeyci-kaniko:latest"
    :display-name "monkeyci-kaniko-args"
    :arguments ["-c" "echo test"]}
   ;; Exit 128
   #_{:image-url "fra.ocir.io/frjdhmocn5qi/monkeyci-kaniko:latest"
    :display-name "monkeyci-kaniko-busybox-cmd-args"
    :command ["/busybox/sh"]
    :arguments ["-c" "echo test"]}
   ;; Exit 128
   #_{:image-url "fra.ocir.io/frjdhmocn5qi/monkeyci-kaniko:latest"
    :display-name "monkeyci-kaniko-cmd-args"
    :command ["/bin/sh"]
    :arguments ["-c" "echo test"]}
   ;; Exit 128
   #_{:image-url "fra.ocir.io/frjdhmocn5qi/monkeyci-kaniko:latest"
    :display-name "monkeyci-kaniko-cmd"
    :command ["/bin/sh" "-c" "echo test"]}
   ;; Exit 128
   #_{:image-url "fra.ocir.io/frjdhmocn5qi/monkeyci-kaniko:latest"
    :display-name "monkeyci-kaniko-busybox-cmd"
    :command ["/busybox/sh" "-c" "echo test"]}])

(defn- fetch-logs [client inst]
  (let [cid (-> inst :containers first :container-id)]
    (println "Fetching logs for container" cid)
    (md/chain
     (ci/retrieve-logs client {:container-id cid})
     (fn [{:keys [status] :as r}]
       (if (= 200 status)
         (println "Logs:" (:body r))
         (println "Got invalid status code:" status))))))

(defn- handle-event [client evt]
  (when (= "ACTIVE" (get-in evt [:details :lifecycle-state]))
    (println "Instance is running:" (get-in evt [:details :display-name]))
    (mt/in 4000 #(fetch-logs client (:details evt)))))

(defn run-test-container [opts]
  (let [conf (co/oci-container-config)
        client (ci/make-context conf)
        ic (-> (ro/instance-config conf {})
               (assoc :display-name (:display-name opts)
                      :containers [(dissoc opts :shape)])
               (mc/assoc-some :shape (:shape opts))
               (dissoc :volumes))]
    (log/info "Running instance with config:" ic)
    (oci/run-instance client ic {:delete? true
                                 :poll-interval 1000
                                 :post-event (partial handle-event client)})))

(defn run-tests [configs]
  (->> configs
       (map (fn [c]
              (md/chain
               (run-test-container c)
               (partial hash-map :config c :result))))
       (apply md/zip)))

#_(defn run-kaniko-test []
    (let [conf (co/oci-container-config)
          client (ci/make-context conf)
          ic (-> (ro/instance-config conf {})
                 (assoc :display-name "kaniko-test"
                        ;; x86 shape
                        ;;:shape "CI.Standard.E4.Flex"
                        :containers [{ ;;:image-url "gcr.io/kaniko-project/executor:v1.21.0-debug"
                                      ;;:image-url "fra.ocir.io/frjdhmocn5qi/monkeyci-kaniko:latest"
                                      :image-url "docker.io/busybox:latest"
                                      :display-name "kaniko-test"
                                      :command ["/bin/sh"]
                                      ;; Always fails with exit code 128, except for the 'executor'
                                      :arguments ["-c" "echo ok"]}])
                 (dissoc :volumes))]
      (log/info "Running instance with config:" ic)
      (oci/run-instance client ic)))

(defn print-container-logs [client {{:keys [lifecycle-state] :as s} :details}]
  (println "Got state change:" lifecycle-state)
  (when (= "ACTIVE" lifecycle-state)
    (let [cid (-> s :containers first :container-id)]
      (println "Retrieving container logs for" cid)
      (md/chain
       (ci/retrieve-logs client {:container-id cid})
       :body
       println))))

(defn pinp-test
  "Trying to get podman-in-podman to run on a container instance."
  []
  (let [conf (co/oci-container-config)
        client (ci/make-context conf)
        ic (-> (ro/instance-config conf {:build {:build-id "podman-in-container"}})
               (assoc :containers [{:image-url "fra.ocir.io/frjdhmocn5qi/pinp:latest"
                                    :display-name "pinp"
                                    :arguments ["podman" "info"]
                                    :security-context
                                    {:security-context-type "LINUX"
                                     :run-as-user 1000}}])
               (dissoc :volumes))]
    (oci/run-instance client ic (partial print-container-logs client))))

(defn delete-container-instance
  "Deletes container instance with given name"
  [n]
  (let [conf (co/oci-container-config)
        client (ci/make-context conf)]
    (md/chain
     (ci/list-container-instances client (select-keys conf [:compartment-id]))
     :body
     :items
     (partial filter (fn [{:keys [lifecycle-state display-name]}]
                       (and (not (contains? #{"DELETING" "DELETED"} lifecycle-state))
                            (= n display-name))))
     first
     :id
     #(ci/delete-container-instance client {:instance-id %}))))

(defn run-container-with-sidecar
  "Runs a container in OCI that deploys a sidecar"
  [& [{:keys [version ws] :or {version "latest"}}]]
  (let [build-id (str "test-build-" (System/currentTimeMillis))
        pipeline "test-pipe"
        rt (-> @co/global-config
               (config/normalize-config {} {})
               (assoc-in [:containers :image-tag] version)
               (rt/config->runtime)
               (assoc :step
                      {:container/image "docker.io/alpine:latest"
                       :script ["echo 'Hi, this is a simple test.  Waiting for a bit...'"
                                "sleep 5"]
                       :index 0}
                      :pipeline {:name pipeline}
                      :build
                      (cond-> {:build-id build-id
                               :sid ["test-customer" "test-repo" build-id]
                               :checkout-dir oci-cont/work-dir}
                        ws (assoc :workspace ws))))]
    (md/future (c/run-container rt))))

(defn- instance-call
  ([f conf-fn res-fn]
   (let [conf (co/oci-container-config)
         client (ci/make-context conf)]
     (md/chain
      (f client (conf-fn conf))
      :body
      res-fn)))
  ([f conf-fn]
   (instance-call f conf-fn identity)))

(defn list-instances []
  (instance-call ci/list-container-instances #(select-keys % [:compartment-id]) :items))

(defn get-instance [id]
  (instance-call ci/get-container-instance (constantly {:instance-id id})))

(defn get-container [id]
  (instance-call ci/get-container (constantly {:container-id id})))

(defn delete-instances
  "Deletes all instances where the name matches given regex"
  [re]
  (md/chain
   (list-instances)
   (partial filter (comp (partial = "INACTIVE") :lifecycle-state))
   (partial filter (comp (partial re-matches re) :display-name))
   (fn [m]
     (println "Deleting" (count m) "container instances...")
     (let [conf (co/oci-container-config)
           client (ci/make-context conf)]
       (md/loop [td m]
         (when (not-empty td)
           (md/chain
            (ci/delete-container-instance client {:instance-id (:id (first td))})
            (fn [_]
              (md/recur (rest td))))))))))
