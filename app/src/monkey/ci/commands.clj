(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [clojure.tools.logging :as log]
            [monkey.ci
             [build :as b]
             [jobs :as jobs]
             [runtime :as rt]
             [sidecar :as sidecar]
             [utils :as u]]
            [monkey.ci.web.handler :as h]
            [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [manifold
             [deferred :as md]
             [time :as mt]]))

(defn prepare-build-ctx
  "Updates the runtime for the build runner, by adding a `build` object"
  [rt]
  (assoc rt :build (b/make-build-ctx rt)))

;; (defn- print-result [state]
;;   (log/info "Build summary:")
;;   (let [{:keys [pipelines]} @state]
;;     (doseq [[pn p] pipelines]
;;       (log/info "Pipeline:" pn)
;;       (doseq [[sn {:keys [name status start-time end-time]}] (:steps p)]
;;         (log/info "  Step:" (or name sn)
;;                   ", result:" (clojure.core/name status)
;;                   ", elapsed:" (- end-time start-time) "ms")))))

;; (defn- report-evt [ctx e]
;;   (rt/report ctx
;;              {:type :build/event
;;               :event e}))

;; (defn result-accumulator
;;   "Returns a map of event types and handlers that can be registered in the bus.
;;    These handlers will monitor the build progress and update an internal state
;;    accordingly.  When the build completes, the result is logged."
;;   [ctx]
;;   (let [state (atom {})
;;         now (fn [] (System/currentTimeMillis))]
;;     {:state state
;;      :handlers
;;      {:step/start
;;       (fn [{:keys [index name pipeline] :as e}]
;;         (report-evt ctx e)
;;         (swap! state assoc-in [:pipelines (:name pipeline) :steps index] {:start-time (now)
;;                                                                           :name name}))
;;       :step/end
;;       (fn [{:keys [index pipeline status] :as e}]
;;         (report-evt ctx e)
;;         (swap! state update-in [:pipelines (:name pipeline) :steps index]
;;                assoc :end-time (now) :status status))
;;       :build/completed
;;       (fn [_]
;;         (print-result state))}}))

;; (defn register-all-handlers [bus m]
;;   (when bus
;;     (doseq [[t h] m]
;;       (e/register-handler bus t h))))

(defn run-build
  "Performs a build, using the runner from the context.  Returns a deferred
   that will complete when the build finishes."
  [rt]
  (let [r (:runner rt)]
    #_(report-evt ctx {:type :script/start})
    #_(register-all-handlers event-bus (:handlers acc))
    (-> rt
        (prepare-build-ctx)
        (r))))

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
         bs/to-reader)
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
    (try
      (rt/post-events rt {:type :sidecar/start
                          :sid sid
                          :job (jobs/job->event job)})
      (-> (sidecar/run rt)
          (deref)
          :exit-code)
      (finally
        (log/info "Sidecar terminated")
        ;; FIXME The process shuts down immediately after this, so it may occur
        ;; that the event is never sent.  So until this is fixed, we just wait
        ;; a few seconds.
        (rt/post-events rt {:type :sidecar/end
                            :sid sid
                            :job (jobs/job->event job)})
        (when-not (rt/dev-mode? rt)
          (Thread/sleep 2000))))))
