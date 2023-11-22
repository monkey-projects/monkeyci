(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [monkey.ci
             [context :refer [report]]
             [events :as e]
             [storage :as st]
             [utils :as u]]
            [aleph.http :as http]
            [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [manifold.deferred :as md]
            [org.httpkit.client :as hk]))

(defn- maybe-set-git-opts [{{:keys [git-url branch commit-id dir]} :args :as ctx}]
  (cond-> ctx
    git-url (update :build merge {:git {:url git-url
                                        :branch (or branch "main")
                                        :id commit-id}
                                  ;; Overwrite script dir cause it will be calculated by the git checkout
                                  :script-dir dir})))

(defn- includes-build-id? [sid]
  (= 4 (count sid)))

(defn prepare-build-ctx
  "Updates the context for the build runner, by adding a `build` object"
  [{:keys [work-dir] :as ctx}]
  (let [orig-sid (some->> (get-in ctx [:args :sid])
                          (u/parse-sid)
                          (take 4))
        ;; Either generate a new build id, or use the one given
        sid (st/->sid (if (or (empty? orig-sid) (includes-build-id? orig-sid))
                        orig-sid
                        (concat orig-sid [(u/new-build-id)])))
        id (or (last sid) (u/new-build-id))]
    (-> ctx
        ;; Prepare the build properties
        (assoc :build {:build-id id
                       :checkout-dir work-dir
                       :script-dir (u/abs-path work-dir (get-in ctx [:args :dir]))
                       :pipeline (get-in ctx [:args :pipeline])
                       :sid sid})
        (maybe-set-git-opts))))

(defn- print-result [state]
  (log/info "Build summary:")
  (let [{:keys [pipelines]} @state]
    (doseq [[pn p] pipelines]
      (log/info "Pipeline:" pn)
      (doseq [[sn {:keys [name status start-time end-time]}] (:steps p)]
        (log/info "  Step:" (or name sn)
                  ", result:" (clojure.core/name status)
                  ", elapsed:" (- end-time start-time) "ms")))))

(defn- report-evt [ctx e]
  (report ctx
          {:type :build/event
           :event e}))

(defn result-accumulator
  "Returns a map of event types and handlers that can be registered in the bus.
   These handlers will monitor the build progress and update an internal state
   accordingly.  When the build completes, the result is logged."
  [ctx]
  (let [state (atom {})
        now (fn [] (System/currentTimeMillis))]
    {:state state
     :handlers
     {:step/start
      (fn [{:keys [index name pipeline] :as e}]
        (report-evt ctx e)
        (swap! state assoc-in [:pipelines (:name pipeline) :steps index] {:start-time (now)
                                                                          :name name}))
      :step/end
      (fn [{:keys [index pipeline status] :as e}]
        (report-evt ctx e)
        (swap! state update-in [:pipelines (:name pipeline) :steps index]
               assoc :end-time (now) :status status))
      :build/completed
      (fn [_]
        (print-result state))}}))

(defn register-all-handlers [bus m]
  (when bus
    (doseq [[t h] m]
      (e/register-handler bus t h))))

(defn run-build
  "Performs a build, using the runner from the context"
  [{:keys [work-dir event-bus] :as ctx}]
  (let [r (:runner ctx)
        acc (result-accumulator ctx)]
    (report-evt ctx {:type :script/start})
    (register-all-handlers event-bus (:handlers acc))
    (-> ctx
        (prepare-build-ctx)
        (r))))

(def api-url (comp :url :account))

(defn list-builds [{:keys [account] :as ctx}]
  (->> (hk/get (apply format "%s/customer/%s/project/%s/repo/%s/builds"
                      ((juxt :url :customer-id :project-id :repo-id) account))
               {:headers {"accept" "application/edn"}})
       (deref)
       :body
       (bs/to-reader)
       (u/parse-edn)
       (hash-map :type :build/list :builds)
       (report ctx)))

(defn http-server
  "Does nothing but return a channel that will never close.  The http server 
   should already be started by the component system."
  [ctx]
  (report ctx (-> ctx
                  (select-keys [:http])
                  (assoc :type :server/started)))
  (ca/chan))

(defn watch
  "Starts listening for events and prints the results.  The arguments determine
   the event filter (all for a customer, project, or repo)."
  [{:keys [event-bus] :as ctx}]
  (let [url (api-url ctx)
        ch (ca/chan)
        pipe-events (fn [r]
                      (let [read-next (fn [] (u/parse-edn r {:eof ::done}))]
                        (loop [m (read-next)]
                          (if (= ::done m)
                            (do
                              (log/info "Event stream closed")
                              (ca/offer! ch 0) ; Exit code 0
                              (ca/close! ch))
                            (do
                              (log/debug "Got event:" m)
                              (report ctx {:type :build/event :event m})
                              (recur (read-next)))))))]
    (log/info "Watching the server at" url "for events...")
    (report ctx {:type :watch/started
                 :url url})
    ;; TODO Trailing slashes
    ;; TODO Customer and other filtering
    ;; Unfortunately, http-kit can't seem to handle SSE, so we use Aleph instead
    (-> (md/chain
         (http/get (str url "/events"))
         :body
         bs/to-reader)
        (md/on-realized pipe-events
                        (fn [err]
                          (log/error "Unable to receive server events:" err))))
    ;; cli-matic will wait for this channel to close
    ch))

(defn- maybe-create-file [f]
  (when-not (fs/exists? f)
    (fs/create-file f))
  f)

(defn sidecar
  "Runs the application as a sidecar, that is meant to capture events 
   and logs from a container process.  This is necessary because when
   running containers from third-party images, we don't have control
   over them.  Instead, we launch a sidecar and execute the commands
   in the container in a script that writes events and output to files,
   which are then picked up by the sidecar to dispatch or store.  

   The sidecar loop will stop when the events file is deleted."
  [{:keys [event-bus] :as ctx}]
  (let [f (-> (get-in ctx [:args :events-file])
              (maybe-create-file))
        read-next (fn [r]
                    (u/parse-edn r {:eof ::eof}))
        interval (get-in ctx [:sidecar :poll-interval] 1000)]
    (log/info "Starting sidecar, reading events from" f)
    (ca/thread
      (with-open [r (io/reader f)]
        (loop [evt (read-next r)]
          (if (not (fs/exists? f))
            0 ;; Done when the events file is deleted
            (when (if (= ::eof evt)
                    (do
                      (Thread/sleep interval)
                      true)
                    (do
                      (log/debug "Read next event:" evt)
                      (e/post-event event-bus evt)))
              (recur (read-next r)))))))))
