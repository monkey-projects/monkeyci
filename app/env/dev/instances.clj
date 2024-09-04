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
             [protocols :as p]
             [runtime :as rt]
             [utils :as u]]
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

(defn config->client []
  (-> (co/oci-container-config)
      (ci/make-context)))

(defn print-container-logs
  ([client cid]
   (println "Fetching logs for container" cid)
   (md/chain
    (ci/retrieve-logs client {:container-id cid})
    (fn [{:keys [status] :as r}]
      (if (= 200 status)
        (println "Logs:" (:body r))
        (println "Got invalid status code:" status)))))
  ([cid]
   (print-container-logs (config->client) cid)))

(defn- fetch-logs [client inst]
  (print-container-logs client (-> inst :containers first :container-id)))

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
               ;; Dummy api config
               (assoc :api {:url "http://test"
                            :port 12243
                            :token "dummy-token"})
               (rt/config->runtime)
               (assoc :job
                      {:id "test-job"
                       :container/image "docker.io/alpine:latest"
                       :script ["echo 'Hi, this is a simple test.  Waiting for a bit...'"
                                "sleep 5"]
                       :index 0}
                      :build
                      (cond-> {:build-id build-id
                               :sid ["test-customer" "test-repo" build-id]
                               :checkout-dir oci-cont/work-dir}
                        ws (assoc :workspace ws))))
        runner (c/make-container-runner rt)]
    (p/run-container runner (:job rt))))

(defn- throw-error! [{:keys [status] :as resp}]
  (if (or (nil? status) (>= status 400))
    (throw (ex-info "Got remote error" resp))
    resp))

(defn- instance-call
  ([f conf-fn res-fn]
   (let [conf (co/oci-container-config)
         client (ci/make-context conf)]
     (md/chain
      (f client (conf-fn conf))
      throw-error!
      :body
      res-fn)))
  ([f conf-fn]
   (instance-call f conf-fn identity)))

(defn list-instances []
  (instance-call ci/list-container-instances #(select-keys % [:compartment-id]) :items))

(defn list-active []
  (md/chain
   (list-instances)
   (partial filter (comp (partial = "ACTIVE") :lifecycle-state))))

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

(defn print-job-logs
  "Prints the logs of the (active) job container in the given instance"
  [iid]
  (md/chain
   (get-instance iid)
   :containers
   (partial filter (comp (partial = "job") :display-name))
   first
   :container-id
   print-container-logs))

(defn run-build
  "Runs a build given the specified GIT url and branch name, using the current config."
  [url branch]
  (let [conf (-> @co/global-config
                 (config/normalize-config {} {}))]
    (rt/with-runtime conf :server rt
      (let [runner (:runner rt)
            build {:build-id (str "test-build-" (u/now))
                   :git {:url url
                         :branch branch
                         :sid (co/account->sid)}}]
        (runner (assoc rt :build build))))))
