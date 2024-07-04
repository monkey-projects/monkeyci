(ns monkey.ci.logging.loki
  "Logging implementation that sends to Loki.  This is useful for the container
   implementation, where promtail is problematic because it never shuts down."
  (:require [aleph.http :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci.logging :as l]))

(defn post-logs
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

(defn stream-to-loki
  "Pipes the given reader to Loki asynchronously.  Stops when the reader is
   closed.  Returns a deferred that resolves when the streaming stops."
  [reader conf]
  (let [r (java.io.LineNumberReader. reader)
        res (md/deferred)]
    (-> (Thread.
         #(loop [l (.readLine r)]
            (log/debug "Read line:" l)
            (if l
              (recur (.readline r))
              (md/success! res {:line-count (.getLineNumber r)}))))
        (.start))
    res))

(defrecord LokiLogger [conf rt path]
  l/LogCapturer
  (log-output [this]
    :stream)
  
  (handle-stream [this in]
    ;; TODO Upload to loki
    ))
