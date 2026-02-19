(ns monkey.ci.logging.log-ingest
  "Client for log ingestion microservice"
  (:require [aleph.http :as http]
            [babashka.fs :as fs]
            [clj-commons.byte-streams :as bs]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [stream :as ms]
             [time :as mt]]
            [monkey.ci
             [build :as b]
             [logging :as l]
             [utils :as u]]
            [monkey.flow :as flow]))

(defn make-client [conf]
  (fn [req & args]
    (-> (condp = req
          :push
          (let [b (pr-str {:entries (second args)})]
            {:method :post
             :body b
             :headers {"content-type" "application/edn"
                       "content-length" (count b)}})
          :fetch
          {:method :get})
        (assoc :url (cs/join "/" (concat [(:url conf) "log"] (first args))))
        (http/request))))

(defn push-logs
  "Pushes given logs at specified path"
  [client path logs]
  (client :push path logs))

(defn fetch-logs
  "Retrieves any logs at specified path"
  [client path]
  (client :fetch path))

(defn pushing-stream
  "Returns an `OutputStream` that pushes the received bytes to the log ingester
   at configured intervals, or after a given number of bytes."
  [client {:keys [path buf-size interval] :or {buf-size 0x10000 interval 1000}}]
  (let [s (ms/stream 1 (flow/buffer-xf buf-size))]
    ;; Flush periodically
    (ms/connect (ms/periodically interval (constantly ::flow/flush)) s {:upstream? true})
    ;; Push any received logs to the log ingester
    (ms/consume-async (fn [logs]
                        (log/trace "Pushing logs of" (count logs) "chars")
                        (-> (push-logs client path [logs])
                            (md/chain (constantly true))))
                      s)
    (flow/raw-stream s)))

(defn- ingest-path [build-sid path]
  (concat build-sid (cs/split path #"/")))

(defrecord LogIngestLogger [client opts build path]
  l/LogCapturer
  (log-output [this]
    (pushing-stream client (assoc opts :path (ingest-path (b/sid build) path))))
  
  (handle-stream [this in]))

(defn make-ingest-logger [client opts build path]
  (->LogIngestLogger client opts build path))

(defrecord LogIngestRetriever [client]
  l/LogRetriever
  (list-logs [this _]
    ;; TODO Must be added on log ingester server first
    [])

  (fetch-log [this build-sid path]
    (-> (fetch-logs client (ingest-path build-sid path))
        (deref)
        :entries
        (bs/to-input-stream))))

(defn make-log-ingest-retriever [client]
  (->LogIngestRetriever client))

(defn stream-file
  "Reads the given logfile to the sink.  When the sink is closed, or eof is reached,
   the streaming operation is stopped.  Additional options can be specified to set
   buffer size and push interval.  Returns a deferred that will hold the reason the
   async loop has stopped."
  [f s & [{:keys [interval buf-size] :or {interval 1000 buf-size 0x10000} :as opts}]]
  (let [r (md/deferred)
        buf (byte-array buf-size)]
    (let [r (io/input-stream (fs/file f))]
      (letfn [(read-next []
                (.read r buf))]
        (-> (md/loop [n (read-next)]
              ;; Continue on eof, only stop when sink is closed
              (md/chain
               (if (neg? n)
                 (md/chain
                  (ms/put! s ::eof)
                  (fn [v]
                    ;; Wait a bit before continuing
                    (mt/in interval (constantly v))))
                 (ms/put! s {:buf buf :off 0 :len n}))
               (fn [v]
                 (if v
                   (md/recur
                    (read-next))
                   ::sink-closed))))
            (md/finally #(.close r)))))))

(defn stream-file-when-exists
  "Waits until file `f` exists, then streams its contents to Manifold sink `s`.
   Returns a deferred that will hold the result of the streaming operation.
   In order to stop reading the file, close the sink."
  [f s & opts]
  (md/chain (u/wait-for-file f (select-keys opts [:period]))
            #(stream-file % s opts)))
