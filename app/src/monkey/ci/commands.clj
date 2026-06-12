(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [aleph.http :as http]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clj-commons.byte-streams :as bs]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
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
            [monkey.ci.app.edn :as edn]
            [monkey.ci.app.events
             [core :as ec]
             [mailman :as em]]
            [monkey.ci.runners
             [controller :as rc]
             [runtime :as rr]]
            [monkey.ci.runtime.app :as ra]
            [monkey.ci.script.core :as script]
            [monkey.ci.sidecar
             [core :as sc]
             [runtime :as rs]
             [spec :as ss]]
            [monkey.ci.web
             [auth :as auth]
             [http :as wh]]))

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
