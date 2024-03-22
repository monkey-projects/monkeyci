(ns monkey.ci.sidecar
  "Sidecar specific functions"
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [medley.core :as mc]
            [monkey.ci
             [artifacts :as art]
             [blob :as blob]
             [build :as b]
             [cache :as cache]
             [config :as c]
             [logging :as l]
             [runtime :as rt]
             [utils :as u]]))

(defn restore-src [{:keys [build] :as rt}]
  (let [store (get rt :workspace)
        ws (:workspace build)
        ;; Check out to the parent because the archive contains the directory
        checkout (some-> (:checkout-dir build)
                         (fs/parent)
                         str)
        restore (fn [rt]
                  (log/info "Restoring workspace" ws)
                  (md/chain
                   (blob/restore store ws checkout)
                   (fn [_]
                     (assoc-in rt [:build :workspace/restored?] true))))]
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

#_(defn- make-rw [f]
  ;; Grant read/write to the world, in case the job runs as another user
  (fs/set-posix-file-permissions f "rw-rw-rw-"))

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
    (let [size (fs/size path)]
      (when (pos? size)
        (log/debug "Uploading log file" path "(" size "bytes)")
        (with-open [is (io/input-stream path)]
          (let [capt (logger [(fs/file-name path)])
                d (l/handle-stream capt is)]
            (when (md/deferred? d)
              @d)))))))

(defn upload-logs
  "Uploads log files referenced in the event, if any"
  [evt logger]
  (doseq [l ((juxt :stdout :stderr) evt)]
    (when l
      (upload-log logger l)
      (log/debug "File uploaded:" l))))

(defn poll-events
  "Reads events from the job container events file and posts them to the event service."
  [rt]
  (let [f (-> (get-config rt :events-file)
              (maybe-create-file))
        read-next (fn [r]
                    (u/parse-edn r {:eof ::eof}))
        interval (get-in rt [rt/config :sidecar :poll-interval] 1000)
        log-maker (rt/log-maker rt)
        log-base (b/get-job-sid rt)
        logger (when log-maker (comp (partial log-maker rt)
                                     (partial concat log-base)))
        set-exit (fn [v] (assoc rt :exit-code v))
        sid (b/get-sid rt)]
    (log/info "Polling events from" f)
    (md/future
      (try
        (with-open [r (io/reader f)]
          (loop [evt (read-next r)]
            (if (not (fs/exists? f))
              ;; Done when the events file is deleted
              (set-exit 0)
              (when (if (= ::eof evt)
                      (do
                        ;; EOF reached, wait a bit and retry
                        (Thread/sleep interval)
                        true)
                      (do
                        (log/debug "Read next event:" evt)
                        (when (contains? evt :exit)
                          (upload-logs evt logger))
                        (rt/post-events rt (assoc evt :sid sid))))
                (if (:done? evt)
                  (set-exit 0)
                  (recur (read-next r)))))))
        (catch Exception ex
          (log/error "Failed to read events" ex)
          (set-exit 1))
        (finally
          (log/debug "Stopped reading events"))))))

(defn run
  "Runs sidecar by restoring workspace, artifacts and caches, and then polling for events.
   After the event loop has terminated, saves artifacts and caches and returns a deferred
   containing the runtime with an `:exit-code` added."
  [rt]
  (log/info "Running sidecar with configuration:" (get-in rt [rt/config :sidecar]))
  ;; Restore caches and artifacts before starting the job
  (let [h (-> (comp poll-events mark-start)
              (art/wrap-artifacts)
              (cache/wrap-caches))]
    (-> rt
        (restore-src)
        (md/chain h)
        (md/catch
            ;; TODO At this point we also should abort the job container by writing to the abort file
            (fn [ex]
              (log/error "Failed to run sidecar" ex)
              {:exit-code 1
               :exception ex})))))

(defn- add-from-args [conf k]
  (update-in conf [:sidecar k] #(or (get-in conf [:args k]) %)))

(defmethod c/normalize-key :sidecar [_ conf]
  (-> conf
      (mc/update-existing-in [:sidecar :log-config] u/try-slurp)
      (add-from-args :events-file)
      (add-from-args :start-file)
      (add-from-args :job-config)))
