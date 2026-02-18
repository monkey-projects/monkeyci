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
             [workspace :as ws]]
            [monkey.ci.events
             [core :as ec]
             [edn :as ee]
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

(defn- make-evt [evt {:keys [job sid]}]
  (ec/make-event
   (:type evt)
   (assoc evt
          :src :job
          :sid sid
          :job-id (j/job-id job))))

(defn- handle-evt [{:keys [mailman] :as rt} evt]
  (log/debug "Read next event:" evt)
  (em/post-events mailman [(make-evt evt rt)]))

(defn- with-exit [h rt]
  (letfn [(set-exit! [v]
            (swap! rt assoc :exit v)
            false)]
    (fn [evt]
      (try
        (let [r (h evt)]
          (if r 
            (if (:done? evt)
              (set-exit! 0)
              r)
            (set-exit! 0)))
        (catch Exception ex
          (log/error "Failed to read events" ex)
          (set-exit! 1))))))

(defn poll-events
  "Reads events from the job container events file and posts them to the event service."
  [rt]
  (let [f (maybe-create-file (get-in rt [:paths :events-file]))
        interval (get rt :poll-interval cs/default-poll-interval)
        a (atom rt)]
    (log/info "Polling events from" f)
    (let [r (io/reader f)]
      (-> (ee/read-edn r (-> (partial handle-evt rt)
                             (ee/sleep-on-eof interval)
                             (ee/stop-on-file-delete f)
                             (with-exit a)))
          ;; Return the runtime, with exit code set
          (md/chain (fn [_] @a))
          (md/finally #(.close r))))))

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
