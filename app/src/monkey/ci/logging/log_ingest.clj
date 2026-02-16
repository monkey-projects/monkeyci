(ns monkey.ci.logging.log-ingest
  "Client for log ingestion microservice"
  (:require [aleph.http :as http]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold
             [deferred :as md]
             [stream :as ms]]
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
