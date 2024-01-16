(ns monkey.ci.sidecar
  "Sidecar specific functions"
  (:require [babashka.fs :as fs]
            [clojure.core.async :as ca]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [events :as e]
             [utils :as u]]))

(defn restore-src [{:keys [build] :as ctx}]
  (let [store (get-in ctx [:workspace :store])
        ws (:workspace build)
        checkout (get-in build [:git :dir])
        restore (fn [ctx]
                  @(md/chain
                    (blob/restore store ws checkout)
                    (fn [_]
                      (assoc-in ctx [:workspace :restored?] true))))]
    (cond-> ctx
      (and store ws checkout)
      (restore))))

(defn mark-start [ctx]
  (let [s (get-in ctx [:args :start-file])]
    (when (not-empty s)
      (fs/create-file s))
    ctx))

(defn- maybe-create-file [f]
  (when-not (fs/exists? f)
    (fs/create-file f))
  f)

(defn poll-events [{:keys [event-bus] :as ctx}]
  (let [f (-> (get-in ctx [:args :events-file])
              (maybe-create-file))
        read-next (fn [r]
                    (u/parse-edn r {:eof ::eof}))
        interval (get-in ctx [:sidecar :poll-interval] 1000)]
    (log/info "Starting sidecar, reading events from" f)
    (ca/thread
      (try
        (with-open [r (io/reader f)]
          (loop [evt (read-next r)]
            ;; TODO Also stop when the process we're monitoring has terminated
            (if (not (fs/exists? f))
              0 ;; Done when the events file is deleted
              (when (if (= ::eof evt)
                      (do
                        ;; EOF reached, wait a bit and retry
                        (Thread/sleep interval)
                        true)
                      (do
                        (log/debug "Read next event:" evt)
                        ;; TODO Dispatch to API instead?
                        (e/post-event event-bus evt)))
                (recur (read-next r))))))
        (catch Exception ex
          (log/error "Failed to read events" ex)
          1)))))

(defn run [ctx]
  (-> ctx
      (restore-src)
      (mark-start)
      (poll-events)))
