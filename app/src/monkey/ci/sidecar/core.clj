(ns monkey.ci.sidecar.core
  "Sidecar specific functions"
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [artifacts :as art]
             [cache :as cache]
             [jobs :as j]
             [spec :as spec]
             [utils :as u]
             [workspace :as ws]]
            [monkey.ci.events
             [core :as ec]
             [mailman :as em]]
            [monkey.ci.sidecar
             [config :as cs]
             [spec :as ss]]))

(defn- create-file-with-dirs [f]
  (let [p (fs/parent f)]
    (when-not (fs/exists? p)
      (log/debug "Creating directory:" p)
      (fs/create-dirs p)))
  (fs/create-file f))

(defn- touch-file [rt k]
  (let [f (get-in rt [:paths k])]
    (when (not-empty f)
      (log/debug "Creating file:" f)
      (create-file-with-dirs f))
    rt))

(defn mark-start [rt]
  (touch-file rt :start-file))

(defn mark-abort [rt]
  (touch-file rt :abort-file))

(defn- maybe-create-file [f]
  (when-not (fs/exists? f)
    (create-file-with-dirs f))
  f)

;; (defn- upload-log [logger path]
;;   (when (and path logger)
;;     (let [size (fs/size path)]
;;       (when (pos? size)
;;         (log/debug "Uploading log file" path "(" size "bytes)")
;;         (with-open [is (io/input-stream path)]
;;           (let [capt (logger [(fs/file-name path)])
;;                 d (l/handle-stream capt is)]
;;             (when  (md/deferred? d)
;;               @d)))))))

;; (defn upload-logs
;;   "Uploads log files referenced in the event, if any"
;;   [evt logger]
;;   (doseq [l ((juxt :stdout :stderr) evt)]
;;     (when l
;;       (upload-log logger l)
;;       (log/debug "File uploaded:" l))))

;; (defn- get-logger [{:keys [sid job log-maker]}]
;;   (let [log-base (conj sid (j/job-id job))]
;;     (when log-maker (comp (partial log-maker build)
;;                           (partial concat log-base)))))

(defn- make-evt [evt {:keys [job sid]}]
  (ec/make-event
   (:type evt)
   (assoc evt
          :src :job
          :sid sid
          :job-id (j/job-id job))))

(defn poll-events
  "Reads events from the job container events file and posts them to the event service."
  [{:keys [mailman] :as rt}]
  (let [f (maybe-create-file (get-in rt [:paths :events-file]))
        read-next (fn [r]
                    (u/parse-edn r {:eof ::eof}))
        interval (get rt :poll-interval cs/default-poll-interval)
        ;;logger (get-logger rt)
        set-exit (fn [v] (assoc rt :exit v))]
    (log/info "Polling events from" f)
    (md/future
      (try
        (with-open [r (java.io.PushbackReader. (io/reader f))]
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
                        ;; Disabled log uploads, we're using promtail now
                        #_(when (contains? evt :exit)
                            ;; TODO Start uploading logs as soon as the file is created instead
                            ;; of when the command has finished.
                            (upload-logs evt logger))
                        (em/post-events mailman [(make-evt evt rt)])))
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
   containing the runtime with an `:exit` added."
  [rt]
  {:pre [(spec/valid? ::ss/runtime rt)]}
  ;; Restore caches and artifacts before starting the job
  (let [h (-> (comp poll-events mark-start)
              (art/wrap-artifacts)
              (cache/wrap-caches))
        error-result (fn [ex]
                       {:exit 1
                        :message (ex-message ex)
                        :exception ex})]
    (try
      (-> rt
          (ws/restore)
          (md/chain h)
          (md/catch
              (fn [ex]
                (log/error "Failed to run sidecar" ex)
                (mark-abort rt)
                (error-result ex))))
      (catch Throwable t
        (log/error "Failed to run sidecar" t)
        (mark-abort rt)
        (md/success-deferred (error-result t))))))
