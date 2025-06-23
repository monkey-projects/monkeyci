(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [aleph.http :as http]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clj-commons.byte-streams :as bs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [errors :as errors]
             [jobs :as jobs]
             [pem :as pem]
             [process :as proc]
             [runtime :as rt]
             [spec :as spec]
             [utils :as u]]
            [monkey.ci.events
             [core :as ec]
             [mailman :as em]]
            [monkey.ci.local
             [config :as lc]
             [runtime :as lr]]
            [monkey.ci.runners
             [controller :as rc]
             [runtime :as rr]]
            [monkey.ci.runtime.app :as ra]
            [monkey.ci.script.core :as script]
            [monkey.ci.sidecar
             [core :as sc]
             [runtime :as rs]]
            [monkey.ci.spec.sidecar :as ss]
            [monkey.ci.web
             [auth :as auth]
             [http :as wh]]))

(defn parse-params [params]
  (letfn [(parse [l]
            (let [idx (.indexOf l "=")]
              (if (neg? idx)
                (throw (ex-info "Invalid parameter" {:param l}))
                {:name (.trim (subs l 0 idx)) :value (.trim (subs l (inc idx)))})))]
    (mapv parse params)))

(defn run-build-local
  "Run a build locally, normally from local source but can also be from a git checkout.
   Returns a deferred that will hold zero if the build succeeds, nonzero if it fails."
  [{{:keys [workdir dir]} :args :as config}]
  (let [wd (fs/create-temp-dir) ; TODO Use subdir of current dir .monkeyci?
        cwd (u/cwd)
        build (cond-> {:checkout-dir (or (some->> workdir
                                                  (u/abs-path cwd)
                                                  (fs/canonicalize)
                                                  str)
                                         cwd)
                       :org-id "local-cust"
                       :repo-id "local-repo"
                       :build-id (b/local-build-id)}
                dir (b/set-script-dir dir))
        conf (-> (select-keys config [:mailman :lib-coords :log-config]) ; Allow override for testing
                 (lc/set-work-dir wd)
                 (lc/set-build build)
                 (lc/set-params (parse-params (get-in config [:args :param]))))]
    (log/info "Running local build for src:" (:checkout-dir build))
    (log/debug "Using working directory" (str wd))
    (lr/start-and-post conf (ec/make-event :build/pending
                                           :build build))))

(defn verify-build
  "Runs a linter on the build script to catch any grammatical errors."
  [conf]
  (ra/with-cli-runtime conf
    (fn [rt]
      (let [res (script/verify (get-in rt [:build :script :script-dir]))]
        (rt/report rt {:type :verify/result :result res})
        (if (->> res
                 (map :result)
                 (every? (partial = :success)))
          0
          errors/error-script-failure)))))

(defn run-tests
  "Runs unit tests declared in the build"
  [conf]
  (ra/with-cli-runtime conf
    (fn [rt]
      (rt/report rt {:type :test/starting
                     :build (:build rt)})
      (:exit @(proc/test! (:build rt) rt)))))

(defn ^:deprecated list-builds [rt]
  (->> (http/get (apply format "%s/customer/%s/repo/%s/builds"
                        ((juxt :url :org-id :repo-id) (rt/account rt)))
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
      (wh/on-server-close http))))

(defn ^:deprecated watch
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

(defn- run-sidecar [{:keys [mailman sid job] :as rt}]
  (let [base-evt {:sid sid
                  :job-id (jobs/job-id job)}
        result (try
                 (em/post-events mailman [(ec/make-event :sidecar/start base-evt)])
                 (let [r @(sc/run rt)
                       e (:exit r)]
                   (ec/make-result (b/exit-code->status e) e (:message r)))
                 (catch Throwable t
                   (ec/exception-result t)))]
    (log/info "Sidecar terminated")
    (em/post-events mailman [(-> (ec/make-event :sidecar/end base-evt)
                                 (ec/set-result result))])
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
  (log/info "Running controller with config:" conf)
  (rr/with-runner-system conf
    (fn [sys]
      (rc/run-controller (:runtime sys)))))

(defn- load-pk
  "If `pk` is a private key, returns it, otherwise assumes it's a file and
   reads it."
  [pk]
  (if (pem/private-key? pk)
    pk
    (do
      (log/debug "Loading private key from" pk)
      (-> pk
          (slurp)
          (pem/pem->private-key)))))

(defn- generate-admin-token
  "Generates administration token using the credentials specified in the arguments"
  [{:keys [username private-key]}]
  (log/debug "Generating admin JWT for user" username)
  (let [pk (load-pk private-key)
        token (-> (auth/sysadmin-token [auth/role-sysadmin username])
                  (auth/augment-payload))]
    (auth/sign-jwt token pk)))

(defn- admin-request [{:keys [api] :as conf} path body]
  (let [token (generate-admin-token conf)
        url (str api path)]
    (letfn [(print-result [res]
              (log/info (json/generate-string res))
              res)
            (->exit-code [res]
              (if (< (:status res) 400)
                0 1))]
      (log/debug "Invoking API endpoint at" url "using token:" token)
      (-> @(http/post url
                      (cond-> {:headers {:content-type "application/json"}
                               :oauth-token token}
                        body (assoc :body (json/generate-string body))))
          (mc/update-existing :body bs/to-string)
          (print-result)
          (->exit-code)))))

(defn issue-creds
  "Issues credits.  Mainly used by cronjobs to automatically issue credits according
   to active subscriptions."
  [{:keys [args issue-creds]}]
  (let [date (jt/format
              :iso-date
              (or (some-> (:date args) (jt/local-date))
                  (jt/local-date)))]
    (log/debug "Issuing credits using arguments:" args)
    (admin-request (merge issue-creds args)
                   "/admin/credits/issue"
                   {:date date})))

(defn cancel-dangling-builds
  "Invokes the reaper endpoint of the configured api, using and admin token."
  [{:keys [args dangling-builds]}]
  (log/debug "Canceling dangling builds using args:" args)
  (admin-request (merge dangling-builds args)
                 "/admin/reaper"
                 nil))
