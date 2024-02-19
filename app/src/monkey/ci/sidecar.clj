(ns monkey.ci.sidecar
  "Sidecar specific functions"
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [blob :as blob]
             [config :as c]
             [logging :as l]
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
    (log/info "Restoring workspace" ws)
    (cond-> rt
      (and store ws checkout)
      (restore))))

(defn- get-config [rt k]
  (-> rt rt/config :sidecar k))

(defn- create-file-with-dirs [f]
  (let [p (fs/parent f)]
    (when-not (fs/exists? p)
      (log/debug "Creating directory:" p)
      (fs/create-dirs p)))
  (fs/create-file f))

(defn mark-start [rt]
  (let [s (get-config rt :start-file)]
    (when (not-empty s)
      (log/debug "Creating start file:" s)
      (create-file-with-dirs s))
    rt))

(defn- maybe-create-file [f]
  (when-not (fs/exists? f)
    (create-file-with-dirs f))
  f)

(defn- upload-log [logger path]
  (when (and path logger)
    (let [n (fs/file-name path)
          is (io/input-stream path)
          capt (logger path)]
      (l/handle-stream capt is))))

(defn upload-logs [evt logger]
  (doseq [l ((juxt :stdout :stderr) evt)]
    (upload-log logger l)))

(defn poll-events [rt]
  (let [f (-> (get-config rt :events-file)
              (maybe-create-file))
        read-next (fn [r]
                    (u/parse-edn r {:eof ::eof}))
        interval (get-in rt [rt/config :sidecar :poll-interval] 1000)
        log-maker (rt/log-maker rt)
        logger (when log-maker (partial log-maker rt))]
    (log/info "Polling events from" f)
    (md/future
      (try
        (with-open [r (io/reader f)]
          (loop [evt (read-next r)]
            ;; TODO Also stop when the process we're monitoring has terminated without telling us
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
                        (upload-logs evt logger)
                        (rt/post-events rt evt)))
                (if (:done? evt)
                  0
                  (recur (read-next r)))))))
        (catch Exception ex
          (log/error "Failed to read events" ex)
          1)
        (finally
          (log/debug "Stopped reading events"))))))

(defn run [rt]
  (log/info "Running sidecar with configuration:" (get-in rt [rt/config :sidecar]))
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
