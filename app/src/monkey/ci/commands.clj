(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [aleph.http :as http]
            [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clj-kondo.core :as clj-kondo]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [errors :as err]
             [jobs :as jobs]
             [process :as proc]
             [protocols :as p]
             [runtime :as rt]
             [script :as script]
             [sidecar :as sidecar]
             [spec :as spec]
             [utils :as u]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.runners.controller :as rc]
            [monkey.ci.runtime
             [app :as ra]
             [sidecar :as rs]]
            [monkey.ci.spec.sidecar :as ss]
            [monkey.ci.web.handler :as h]))

(defn run-build
  "Performs a build, using the runner from the context.  Returns a deferred
   that will complete when the build finishes."
  [config]
  (ra/with-runner-system config
    (fn [{:keys [runner events build]}]
      (try
        (log/debug "Starting runner for build" build)
        (runner)
        (catch Exception ex
          (log/error "Unable to start build" ex)
          (ec/post-events events (b/build-end-evt (assoc build :message (ex-message (or (ex-cause ex) ex)))
                                                  err/error-process-failure))
          err/error-process-failure)))))

(defn run-build-local
  "Run a build locally, normally from local source but can also be from a git checkout."
  ;; TODO
  [config])

;; (defn- deps-exists? [rt]
;;   (fs/exists? (fs/path (get-in rt [:build :script :script-dir]) "deps.edn")))

;; (defn- verify-child-proc
;;   "Starts a child process to perform verification.  This is necessary if a `deps.edn`
;;    exists with custom dependencies."
;;   [rt]
;;   ;; TODO Need to get back reported info.  Using a UDS?
;;   1)

;; (defn- verify-in-proc
;;   "Verifies the build in the current directory by loading the script files in-process
;;    and resolving the jobs.  This is useful when checking if there are any compilation
;;    errors in the script."
;;   [rt]
;;   (letfn [(report [rep]
;;             (rt/report rt rep)
;;             (if (= :verify/success (:type rep)) 0 err/error-script-failure))]
;;     (try
;;       ;; TODO Git branch and other options
;;       ;; TODO Build parameters
;;       (let [jobs (script/load-jobs (:build rt) rt)]
;;         (report
;;          (if (not-empty jobs)
;;            {:type :verify/success
;;             :jobs jobs}
;;            {:type :verify/failed
;;             :message "No jobs found in build script for the active configuration"})))
;;       (catch Exception ex
;;         (log/error "Error verifying build" ex)
;;         (report {:type :verify/failed
;;                  :message (ex-message ex)})))))

(defn verify-build
  "Runs a linter agains the build script to catch any grammatical errors."
  [conf]
  (ra/with-cli-runtime conf
    (fn [rt]
      (let [res (clj-kondo/run! {:lint [(get-in rt [:build :script :script-dir])]})]
        (rt/report rt {:type :verify/result :result res})
        (-> res
            :summary
            :error)))))

(defn run-tests
  "Runs unit tests declared in the build"
  [conf]
  (ra/with-cli-runtime conf
    (fn [rt]
      (rt/report rt {:type :test/starting
                     :build (:build rt)})
      (:exit @(proc/test! (:build rt) rt)))))

(defn list-builds [rt]
  (->> (http/get (apply format "%s/customer/%s/repo/%s/builds"
                        ((juxt :url :customer-id :repo-id) (rt/account rt)))
                 {:headers {"accept" "application/edn"}})
       (deref)
       :body
       (bs/to-reader)
       (u/parse-edn)
       (hash-map :type :build/list :builds)
       (rt/report rt)))

(defn http-server
  "Starts a system with an http server.  Dependency management will take care of
   creating and starting the necessary modules."
  [conf]
  (ra/with-server-system conf
    (fn [{rt :runtime :keys [http]}]
      (rt/report rt (-> rt
                        (rt/config)
                        (select-keys [:http])
                        (assoc :type :server/started)))
      ;; Wait for server to stop
      (h/on-server-close http))))

(defn watch
  "Starts listening for events and prints the results.  The arguments determine
   the event filter (all for a customer, project, or repo)."
  [rt]
  (let [url (rt/api-url rt)
        d (md/deferred)
        pipe-events (fn [r]
                      (let [read-next (fn [] (u/parse-edn r {:eof ::done}))]
                        (loop [m (read-next)]
                          (if (= ::done m)
                            (do
                              (log/info "Event stream closed")
                              (md/success! d 0)) ; Exit code 0
                            (do
                              (log/debug "Got event:" m)
                              (rt/report rt {:type :build/event :event m})
                              (recur (read-next)))))))]
    (log/info "Watching the server at" url "for events...")
    (rt/report rt {:type :watch/started
                   :url url})
    ;; TODO Trailing slashes
    ;; TODO Customer and other filtering
    (-> (md/chain
         (http/get (str url "/events"))
         :body
         bs/to-reader
         #(java.io.PushbackReader. %)
         pipe-events)
        (md/catch (fn [err]
                    (log/error "Unable to receive server events:" err))))
    ;; Return a deferred that only resolves when the event stream stops
    d))

(defn- sidecar-rt->job [rt]
  (get-in rt [rt/config :args :job-config :job]))

(defn- ^:deprecated ->sidecar-rt
  "Creates a runtime for the sidecar from the generic runtime.  To be removed."
  [rt]
  (let [conf (get-in rt [rt/config :sidecar])
        args (get-in rt [rt/config :args])]
    (-> rt
        (select-keys [:build :events :workspace :artifacts :cache])
        (assoc :job (sidecar-rt->job rt)
               :log-maker (rt/log-maker rt)
               :paths (select-keys args [:events-file :start-file :abort-file]))
        (mc/assoc-some :poll-interval (get-in rt [rt/config :sidecar :poll-interval])))))

(defn- run-sidecar [{:keys [events job] :as rt}]
  (let [sid (b/get-sid rt)
        base-evt {:sid sid
                  :job-id (jobs/job-id job)}
        result (try
                 (p/post-events events (ec/make-event :sidecar/start base-evt))
                 (let [r @(sidecar/run rt)
                       e (:exit r)]
                   (ec/make-result (b/exit-code->status e) e (:message r)))
                 (catch Throwable t
                   (ec/exception-result t)))]
    (log/info "Sidecar terminated")
    (p/post-events events (-> (ec/make-event :sidecar/end base-evt)
                              (ec/set-result result)))
    (:exit result)))

(defn sidecar
  "Runs the application as a sidecar, that is meant to capture events 
   and logs from a container process.  This is necessary because when
   running containers from third-party images, we don't have control
   over them.  Instead, we launch a sidecar and execute the commands
   in the container in a script that writes events and output to files,
   which are then picked up by the sidecar to dispatch or store.  

   The sidecar loop will stop when the events file is deleted."
  [conf]
  (spec/valid? ::ss/config conf)
  (log/info "Running sidecar with config:" conf)
  (rs/with-runtime conf run-sidecar))

(defn controller
  "Runs the application as a controller.  This is similar to a sidecar,
   but for build processes (usually running in cloud containers).  The
   controller is responsible for preparing the build workspace and running
   an API server that is used by the build script, which then runs alongside
   the controller."
  [conf]
  (ra/with-runner-system conf
    (fn [sys]
      (rc/run-controller (:runtime sys)))))
