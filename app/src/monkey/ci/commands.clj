(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [clojure.spec.alpha :as spec]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [jobs :as jobs]
             [protocols :as p]
             [runtime :as rt]
             [script :as script]
             [sidecar :as sidecar]
             [utils :as u]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.runtime
             [app :as ra]
             [sidecar :as rs]]
            [monkey.ci.spec.sidecar :as ss]
            [monkey.ci.web.handler :as h]))

(def exit-error 1)

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
          (ec/post-events events (b/build-end-evt (assoc build :message (ex-message ex))
                                                  exit-error))
          exit-error)))))

(defn run-build-local
  "Run a build locally, normally from local source but can also be from a git checkout."
  ;; TODO
  [config])

(defn verify-build
  "Verifies the build in the current directory by loading the script files in-process
   and resolving the jobs.  This is useful when checking if there are any compilation
   errors in the script."
  [rt]
  (letfn [(report [rep]
            (rt/report rt rep)
            (if (= :verify/success (:type rep)) 0 exit-error))]
    (try
      ;; TODO Git branch and other options
      ;; TODO Use child process if there is a deps.edn
      ;; TODO Build parameters
      (let [jobs (script/load-jobs (b/make-build-ctx rt) rt)]
        (report
         (if (not-empty jobs)
           {:type :verify/success
            :jobs jobs}
           {:type :verify/failed
            :message "No jobs found in build script for the active configuration"})))
      (catch Exception ex
        (log/error "Error verifying build" ex)
        (report {:type :verify/failed
                 :message (ex-message ex)})))))

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
  "Starts the server by invoking the function in the runtime.  This function is supposed
   to return another function that can be invoked to stop the http server.  Returns a 
   deferred that resolves when the server is stopped."
  [{:keys [http] :as rt}]
  (rt/report rt (-> rt
                    (rt/config)
                    (select-keys [:http])
                    (assoc :type :server/started)))
  ;; Start the server and wait for it to shut down
  (h/on-server-close (http rt)))

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
                  :job (jobs/job->event job)}
        result (try
                 (p/post-events events (assoc base-evt :type :sidecar/start))
                 (let [r @(sidecar/run rt)
                       e (:exit r)]
                   (ec/make-result (b/exit-code->status e) e (:message r)))
                 (catch Throwable t
                   (ec/exception-result t)))]
    (log/info "Sidecar terminated")
    (p/post-events events (-> base-evt
                              (assoc :type :sidecar/end)
                              (ec/set-result result)))
    (:exit result)))

(defn- ^:deprecated sidecar-legacy
  "Legacy implementation, run from old generic config"
  [conf]
  (log/warn "Running sidecar in legacy mode. Reason:" (spec/explain-str ::ss/config conf))
  (rt/with-runtime conf :cli rt
    (run-sidecar (->sidecar-rt rt))))

(defn- sidecar-new
  "Run sidecar from spec-compliant config"
  [conf]
  (log/info "Running sidecar with config:" conf)
  (rs/with-runtime conf run-sidecar))

(defn sidecar
  "Runs the application as a sidecar, that is meant to capture events 
   and logs from a container process.  This is necessary because when
   running containers from third-party images, we don't have control
   over them.  Instead, we launch a sidecar and execute the commands
   in the container in a script that writes events and output to files,
   which are then picked up by the sidecar to dispatch or store.  

   The sidecar loop will stop when the events file is deleted."
  [conf]
  (if (spec/valid? ::ss/config conf)
    (sidecar-new conf)
    (sidecar-legacy conf)))
