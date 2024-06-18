(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [clojure.tools.logging :as log]
            [monkey.ci
             [build :as b]
             [jobs :as jobs]
             [runtime :as rt]
             [script :as script]
             [sidecar :as sidecar]
             [utils :as u]]
            [monkey.ci.events.core :as ec]
            [monkey.ci.web.handler :as h]
            [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [manifold.deferred :as md]))

(def exit-error 1)

(defn run-build
  "Performs a build, using the runner from the context.  Returns a deferred
   that will complete when the build finishes."
  [rt]
  (let [r (:runner rt)]
    (try
      (-> rt
          (b/make-build-ctx)
          (r rt))
      (catch Exception ex
        (log/error "Unable to start build" ex)
        (rt/post-events rt (b/build-end-evt (assoc (rt/build rt) :message (ex-message ex))
                                            exit-error))
        exit-error))))

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
      (let [jobs (-> rt
                     (assoc :build (b/make-build-ctx rt))
                     (script/load-jobs))]
        (report
         (if (not-empty jobs)
           {:type :verify/success
            :jobs jobs}
           {:type :verify/failed
            :message "No jobs found in build script"})))
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
         #(java.io.PushbackReader. %))
        (md/on-realized pipe-events
                        (fn [err]
                          (log/error "Unable to receive server events:" err))))
    ;; Return a deferred that only resolves when the event stream stops
    d))

(defn sidecar
  "Runs the application as a sidecar, that is meant to capture events 
   and logs from a container process.  This is necessary because when
   running containers from third-party images, we don't have control
   over them.  Instead, we launch a sidecar and execute the commands
   in the container in a script that writes events and output to files,
   which are then picked up by the sidecar to dispatch or store.  

   The sidecar loop will stop when the events file is deleted."
  [rt]
  (let [sid (b/get-sid rt)
        ;; Add job info from the sidecar config
        {:keys [job] :as rt} (merge rt (get-in rt [rt/config :sidecar :job-config]))]
    (let [result (try
                   (rt/post-events rt {:type :sidecar/start
                                       :sid sid
                                       :job (jobs/job->event job)})
                   (let [r @(sidecar/run rt)
                         e (:exit r)]
                     (ec/make-result (b/exit-code->status e) e (:message r)))
                   (catch Throwable t
                     (ec/exception-result t)))]
      (log/info "Sidecar terminated")
      (rt/post-events rt (-> {:type :sidecar/end
                              :sid sid
                              :job (jobs/job->event job)}
                             (ec/set-result result)))
      (:exit result))))
