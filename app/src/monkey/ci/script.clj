(ns monkey.ci.script
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.core.async :as ca]
            [martian
             [core :as martian]
             [httpkit :as mh]
             [interceptors :as mi]]
            [monkey.ci.build.core :as bc]
            [monkey.ci
             [containers :as c]
             [events :as e]
             [utils :as u]]
            [monkey.ci.containers
             [docker]
             [podman]]
            [org.httpkit.client :as http])
  (:import java.nio.channels.SocketChannel
           [java.net UnixDomainSocketAddress StandardProtocolFamily]))

(defn initial-context [p]
  (assoc bc/success
         :env {}
         :pipeline p))

(defn- script-evt [ctx evt]
  (assoc evt
         :src :script
         :sid (get-in ctx [:build :sid])
         :time (System/currentTimeMillis)))

(defn- post-event [ctx evt]
  (log/trace "Posting event:" evt)
  (if-let [c (get-in ctx [:api :client])]
    (let [{:keys [status] :as r} @(martian/response-for c :post-event (script-evt ctx evt))]
      (when-not (= 202 status)
        (log/warn "Failed to post event, got status" status)
        (log/debug "Full response:" r)))
    (log/warn "Unable to post event, no client configured")))

(defn- wrapped
  "Adds the event poster to the wrapped function.  This is necessary because the `e/wrapped`
   assumes events are posted through the bus."
  [f before after]
  (let [error (fn [& args]
                ;; On error, add the exception to the result of the 'after' event
                (let [ex (last args)]
                  (log/error "Got error:" ex)
                  (assoc (apply after (concat (butlast args) [{}]))
                         :exception (.getMessage ex))))
        w (e/wrapped f before after error)]
    (fn [ctx & more]
      (apply w (assoc ctx :event-poster (partial post-event ctx)) more))))

(defprotocol PipelineStep
  (run-step [s ctx]))

(defn- run-container-step
  "Runs the step in a new container.  How this container is executed depends on
   the configuration passed in from the parent process, specified in the context."
  [ctx]
  (let [{:keys [exit] :as r} (->> (c/run-container ctx)
                                  (merge bc/failure))]
    (cond-> r
      (zero? exit) (merge bc/success))))

(defn ->map [s]
  (if (map? s)
    s
    {:action s
     :name (u/fn-name s)}))

(extend-protocol PipelineStep
  clojure.lang.Fn
  (run-step [f {:keys [step] :as ctx}]
    (log/debug "Executing function:" f)
    ;; If a step returns nil, treat it as success
    (let [r (or (f ctx) bc/success)]
      (if (bc/status? r)
        r
        ;; Recurse
        (run-step r (assoc ctx :step (merge step (->map r)))))))

  clojure.lang.IPersistentMap
  (run-step [{:keys [action] :as step} ctx]
    (log/debug "Running step:" step)
    ;; TODO Make more generic
    (cond
      (some? (:container/image step))
      (run-container-step ctx)
      (fn? action)
      (run-step action ctx)
      :else
      (throw (ex-info "invalid step configuration" {:step step})))))

(defn- make-step-dir-absolute [{:keys [checkout-dir step] :as ctx}]
  (if (map? step)
    (update-in ctx [:step :work-dir]
               (fn [d]
                 (if d
                   (u/abs-path checkout-dir d)
                   checkout-dir)))
    ctx))

(defn- run-step*
  "Runs a single step using the configured runner"
  [initial-ctx]
  (let [{:keys [step] :as ctx} (make-step-dir-absolute initial-ctx)]
    (try
      (log/debug "Running step:" step)
      (run-step step ctx)
      (catch Exception ex
        (log/warn "Step failed:" (.getMessage ex))
        (assoc bc/failure :exception ex)))))

(defn- with-pipeline [{:keys [pipeline] :as ctx} evt]
  (let [p (select-keys pipeline [:name :index])]
    (assoc evt
           :index (get-in ctx [:step :index])
           :pipeline p)))

(defn- step-start-evt [{{:keys [name]} :step :as ctx}]
  (with-pipeline ctx
    (cond-> {:type :step/start
             :message "Step started"}
      (some? name) (-> (assoc :name name)
                       (update :message str ": " name)))))

(defn- step-end-evt [ctx {:keys [status message exception]}]
  (with-pipeline ctx
    (cond-> {:type :step/end
             :message (or message
                          "Step completed")
             :name (get-in ctx [:step :name ])
             :status status}
      (some? exception) (assoc :message (.getMessage exception)
                               :stack-trace (u/stack-trace exception)))))

(def run-step**                         ; TODO Function naming sucks
  (wrapped run-step*
           step-start-evt
           step-end-evt))

(defn- run-steps!
  "Runs all steps in sequence, stopping at the first failure.
   Returns the execution context."
  [initial-ctx idx {:keys [name steps] :as p}]
  (log/info "Running pipeline:" name)
  (log/debug "Running pipeline steps:" p)
  (->> steps
       (map ->map)
       ;; Add index to each step
       (map (fn [i s]
              (assoc s :index i))
            (range))     
       (reduce (fn [ctx s]
                 (let [r (-> ctx
                             (assoc :step s :pipeline (assoc p :index idx))
                             (run-step**))]
                   (log/debug "Result:" r)
                   (when-let [o (:output r)]
                     (log/debug "Output:" o))
                   (when-let [o (:error r)]
                     (log/warn "Error output:" o))
                   (cond-> ctx
                     true (assoc :status (:status r)
                                 :last-result r)
                     (bc/failed? r) (reduced))))
               (merge (initial-context p) initial-ctx))))

(defn- pipeline-start-evt [_ _ {:keys [name]}]
  {:type :pipeline/start
   :pipeline name
   :message (cond-> "Starting pipeline"
              name (str ": " name))})

(defn- pipeline-end-evt [_ _ {:keys [name]} r]
  {:type :pipeline/end
   :pipeline name
   :message "Completed pipeline"
   :status (:status r)})

(def run-steps!*
  (wrapped run-steps!
           pipeline-start-evt
           pipeline-end-evt))

(defn run-pipelines
  "Executes the pipelines by running all steps sequentially.  Currently,
   pipelines are executed sequentially too, but this could be converted 
   into parallel processing."
  [{:keys [pipeline] :as ctx} p]
  (let [p (cond->> (if (vector? p) p [p])
            ;; Filter pipeline by name, if given
            pipeline (filter (comp (partial = pipeline) :name)))]
    (log/debug "Found" (count p) "matching pipelines:" (map :name p))
    (let [result (->> p
                      (map-indexed (partial run-steps!* ctx))
                      (doall))]
      {:status (if (every? bc/success? result) :success :failure)})))

(defn- load-pipelines [dir build-id]
  (let [tmp-ns (symbol (or build-id (str "build-" (random-uuid))))]
    ;; Declare a temporary namespace to load the file in, in case
    ;; it does not declare an ns of it's own.
    (in-ns tmp-ns)
    (clojure.core/use 'clojure.core)
    (try
      (let [path (io/file dir "build.clj")]
        (log/debug "Loading script:" path)
        ;; This should return pipelines to run
        (load-file (str path)))
      (finally
        ;; Return
        (in-ns 'monkey.ci.script)
        (remove-ns tmp-ns)))))

(defn- make-uds-address [path]
  (UnixDomainSocketAddress/of path))

(defn- open-uds-socket []
  (SocketChannel/open StandardProtocolFamily/UNIX))

;; The swagger is fetched by the build script client api
(def swagger-path "/script/swagger.json")

(defn- connect-to-uds [path]
  (let [client (http/make-client
                {:address-finder (fn make-addr [_]
                                   (make-uds-address path))
                 :channel-factory (fn [_]
                                    (open-uds-socket))})
        ;; Martian doesn't pass in the client in the requests, so do it with an interceptor.
        client-injector {:name ::inject-client
                         :enter (fn [ctx]
                                  (assoc-in ctx [:request :client] client))}
        interceptors (-> mh/default-interceptors
                         (mi/inject client-injector :before ::mh/perform-request))]
    ;; Url is not used, but we need the path to the swagger
    (mh/bootstrap-openapi (str "http://fake-host" swagger-path)
                          {:interceptors interceptors}
                          {:client client})))

(defn- connect-to-host [url]
  (mh/bootstrap-openapi (str url swagger-path)))

(defn make-client
  "Initializes a Martian client using the configuration given.  It can either
   connect to a domain socket, or a host.  The client is then added to the
   context, where it can be accessed by the build scripts."
  [{{:keys [url socket]} :api}]
  (log/debug "Connecting to API at" (or url socket))
  (cond
    url (connect-to-host url)
    socket (connect-to-uds socket)))

(defn- setup-api-client [ctx]
  (cond-> ctx
    ;; In tests it could be there is no socket, so skip the initialization in that case
    (get-in ctx [:api :socket]) (assoc-in [:api :client] (make-client ctx))))

(defn- with-script-api [ctx f]
  (let [ctx (setup-api-client ctx)]
    (f ctx)))

(defn- with-script-dir [{:keys [script-dir] :as ctx} evt]
  (->> (assoc evt :dir script-dir)
       (script-evt ctx)))

(defn- script-started-evt [ctx _]
  (with-script-dir ctx
    {:type :script/start
     :message "Script started"}))

(defn- script-completed-evt [ctx & _]
  (with-script-dir ctx
    {:type :script/end
     :message "Script completed"}))

(def run-pipelines*
  (wrapped run-pipelines
           script-started-evt
           script-completed-evt))

(defn- load-and-run-pipelines [{:keys [script-dir] {:keys [build-id]} :build :as ctx}]
  (log/debug "Executing script for build" build-id "at:" script-dir)
  (log/debug "Script context:" ctx)
  (try 
    (let [p (load-pipelines script-dir build-id)]
      (log/debug "Loaded" (count p) "pipelines:" (map :name p))
      (run-pipelines* ctx p))
    (catch Exception ex
      (post-event ctx {:type :script/end
                       :message (.getMessage ex)}))))

(defn exec-script!
  "Loads a script from a directory and executes it.  The script is
   executed in this same process (but in a randomly generated namespace)."
  [ctx]
  (with-script-api ctx load-and-run-pipelines))
