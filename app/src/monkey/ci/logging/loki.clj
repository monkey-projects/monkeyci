(ns monkey.ci.logging.loki
  "Logging implementation that sends to Loki.  This is useful for the container
   implementation, where promtail is problematic because it never shuts down."
  (:require [aleph.http :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci.logging :as l]))

(defn post-to-loki
  "Posts the given log streams to the configured endpoint"
  [{:keys [url tenant-id token]} streams]
  (letfn [(convert [s]
            (update s :values (partial map convert-line)))
          (convert-line [[time v]]
            ;; Convert time to nanoseconds string
            [(str (* time 1000000)) v])]
    (http/post url
               {:body (->> streams
                           (map convert)
                           (hash-map :streams)
                           (json/generate-string))
                :headers (cond-> {}
                           tenant-id (assoc "X-Scope-OrgID" tenant-id)
                           token (assoc "Authorization" (str "Bearer " token)))})))

(defn post-lines
  "Converts lines to a usable stream config for Loki and sends them out"
  [lines {:keys [labels] :as conf}]
  (when-not (empty? lines)
    @(post-to-loki conf
                   [(cond-> {:values lines}
                      labels (assoc :stream labels))])))

(defn post-or-acc
  "Either posts all accumulated lines, or adds it to the list, depending
   on configuration."
  [l acc {:keys [threshold now] :as conf}]
  (let [{:keys [lines timeout] :or {lines 100 timeout 1000}} threshold
        total (conj acc l)
        now (or now (System/currentTimeMillis))
        threshold-reached? (or (>= (count total) lines)
                               ;; TODO Keep track of last push time
                               (>= now timeout))]
    (if threshold-reached?
      (do
        (post-lines total conf)
        [])
      total)))

(defn stream-to-loki
  "Pipes the given reader to Loki asynchronously.  Stops when the reader is
   closed.  Returns a deferred that resolves when the streaming stops."
  [reader conf]
  (let [r (java.io.LineNumberReader. reader)
        read-line (fn []
                    (try
                      (some->> (.readLine r)
                               (vector (System/currentTimeMillis)))
                      (catch Exception ex
                        (log/debug "Cause:" (ex-cause ex))
                        (when (not= "Pipe closed" (ex-message ex))
                          (log/warn "Failed to read next line" ex))
                        nil)))
        res (md/deferred)
        thread (Thread.
                #(loop [l (read-line)
                        acc []]
                   ;; TODO We should also post if no new line has been received for a while
                   (log/debug "Read line:" l)
                   (if l
                     (recur (read-line)
                            (post-or-acc l acc conf))
                     (do
                       (post-lines acc conf)
                       (log/debug "Terminating stream loop")
                       (md/success! res {:line-count (.getLineNumber r)})))))
        interrupt (fn [_]
                    (when (.isAlive thread)
                      (log/debug "Interrupting streaming thread")
                      (.interrupt thread)))]
    (.start thread)
    ;; Ensure thread terminates
    (md/catch res interrupt)))

(defrecord LokiLogger [conf build path]
  l/LogCapturer
  (log-output [this]
    :stream)
  
  (handle-stream [this in]
    (stream-to-loki (io/reader in) conf)))
