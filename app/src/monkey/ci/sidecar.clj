(ns monkey.ci.sidecar
  "Sidecar specific functions"
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [config :as c]
             [runtime :as rt]
             [utils :as u]]))

(defn restore-src [{:keys [build] :as rt}]
  (let [store (get-in rt [:workspace :store])
        ws (:workspace build)
        checkout (:checkout-dir build)
        restore (fn [rt]
                  @(md/chain
                    (blob/restore store ws checkout)
                    (fn [_]
                      (assoc-in rt [:workspace :restored?] true))))]
    (cond-> rt
      (and store ws checkout)
      (restore))))

(defn- get-config [rt k]
  (-> rt rt/config :sidecar k))

(defn mark-start [rt]
  (let [s (get-config rt :start-file)]
    (when (not-empty s)
      (log/debug "Creating start file:" s)
      (fs/create-file s))
    rt))

(defn- maybe-create-file [f]
  (when-not (fs/exists? f)
    (fs/create-file f))
  f)

(defn poll-events [rt]
  (let [f (-> (get-config rt :events-file)
              (maybe-create-file))
        read-next (fn [r]
                    (u/parse-edn r {:eof ::eof}))
        interval (get-in rt [rt/config :sidecar :poll-interval] 1000)]
    (log/info "Starting sidecar, reading events from" f)
    (md/future
      (try
        (with-open [r (io/reader f)]
          (loop [evt (read-next r)]
            ;; TODO Also stop when the process we're monitoring has terminated
            (if (not (fs/exists? f))
              ;; Done when the events file is deleted
              0
              (when (if (= ::eof evt)
                      (do
                        ;; EOF reached, wait a bit and retry
                        (Thread/sleep interval)
                        true)
                      (do
                        (log/debug "Read next event:" evt)
                        (rt/post-events rt evt)))
                (recur (read-next r))))))
        (catch Exception ex
          (log/error "Failed to read events" ex)
          1)
        (finally
          (log/debug "Stopped reading events"))))))

(defn run [rt]
  (-> rt
      (restore-src)
      (mark-start)
      (poll-events)))

(defn- add-from-args [conf k]
  (update-in conf [:sidecar k] #(or (get-in conf [:args k]) %)))

(defmethod c/normalize-key :sidecar [_ conf]
  (-> conf
      (add-from-args :events-file)
      (add-from-args :start-file)))
