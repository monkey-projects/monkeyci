(ns monkey.ci.logging.log-ingest
  "Client for log ingestion microservice"
  (:require [aleph.http :as http]
            [clojure.string :as cs]
            [manifold.time :as mt]
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
