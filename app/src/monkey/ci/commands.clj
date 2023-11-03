(ns monkey.ci.commands
  "Event handlers for commands"
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as ca]
            [clojure
             [edn :as edn]
             [string :as cs]]
            [monkey.ci
             [events :as e]
             [utils :as u]]
            [aleph.http :as http]
            [clj-commons.byte-streams :as bs]
            [manifold.deferred :as md]))

(defn- maybe-set-git-opts [{{:keys [git-url branch commit-id]} :args :as ctx}]
  (cond-> ctx
    git-url (assoc-in [:build :git] {:url git-url
                                     :branch (or branch "main")
                                     :id commit-id})))

(defn- parse-sid [s]
  (when s
    (cs/split s #"/")))

(defn prepare-build-ctx
  "Updates the context for the build runner, by adding a `build` object"
  [{:keys [work-dir] :as ctx}]
  (let [id (u/new-build-id)]
    (-> ctx
        ;; Prepare the build properties
        (assoc :build {:build-id id
                       :checkout-dir work-dir
                       :script-dir (u/abs-path work-dir (get-in ctx [:args :dir]))
                       :pipeline (get-in ctx [:args :pipeline])
                       :sid (some-> (parse-sid (get-in ctx [:args :sid]))
                                    (conj id))})
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

(defn result-accumulator
  "Returns a map of event types and handlers that can be registered in the bus.
   These handlers will monitor the build progress and update an internal state
   accordingly.  When the build completes, the result is logged."
  []
  (let [state (atom {})
        now (fn [] (System/currentTimeMillis))]
    {:state state
     :handlers
     {:step/start
      (fn [{:keys [index name pipeline] :as e}]
        (swap! state assoc-in [:pipelines (:name pipeline) :steps index] {:start-time (now)
                                                                          :name name}))
      :step/end
      (fn [{:keys [index pipeline status] :as e}]
        (swap! state update-in [:pipelines (:name pipeline) :steps index]
               assoc :end-time (now) :status status))
      :build/completed
      (fn [_]
        (print-result state))}}))

(defn register-all-handlers [bus m]
  (when bus
    (doseq [[t h] m]
      (e/register-handler bus t h))))

(defn build
  "Performs a build, using the runner from the context"
  [{:keys [work-dir event-bus] :as ctx}]
  (let [r (:runner ctx)
        acc (result-accumulator)]
    (register-all-handlers event-bus (:handlers acc))
    (-> ctx
        (prepare-build-ctx)
        (r))))

(defn http-server
  "Does nothing but return a channel that will never close.  The http server 
   should already be started by the component system."
  [ctx]
  (ca/chan))

(defn- log-event [e]
  (log/info (:message e)))

(defn watch
  "Starts listening for events and prints the results.  The arguments determine
   the event filter (all for a customer, project, or repo)."
  [{:keys [event-bus] {:keys [url]} :account :as ctx}]
  (let [ch (ca/chan)]
    (log/info "Watching the server at" url "for events...")
    ;; TODO Trailing slashes
    ;; TODO Customer and other filtering
    ;; Unfortunately, http-kit can't seem to handle SSE, so we use Aleph instead
    (-> (md/chain
         (http/get (str url "/events"))
         :body
         bs/to-reader)
        (md/on-realized (fn [r]
                          (let [pbr (java.io.PushbackReader. r)
                                read-next (fn [] (edn/read {:eof ::done} pbr))]
                            (loop [m (read-next)]
                              (if (= ::done m)
                                (do
                                  (log/info "Event stream closed")
                                  (ca/offer! ch 0) ; Exit code 0
                                  (ca/close! ch))
                                (do
                                  (log-event m)
                                  (recur (read-next)))))))
                        (fn [err]
                          (log/error "Unable to receive server events:" err))))
    ;; cli-matic will wait for this channel to close
    ch))
