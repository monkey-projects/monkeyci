(ns monkey.ci.logging.oci
  (:require [clj-commons.byte-streams :as bs]
            [clojure.string :as cs]
            [manifold.deferred :as md]
            [monkey.ci
             [logging :as l]
             [oci :as oci]
             [sid :as sid]]
            [monkey.oci.os.core :as os]))


(defn- sid->prefix [sid {:keys [prefix]}]
  (cond->> (str (cs/join sid/delim sid) sid/delim)
    (some? prefix) (str prefix "/")))

(deftype OciBucketLogRetriever [client conf]
  l/LogRetriever
  (list-logs [_ sid]
    (let [prefix (sid->prefix sid conf)
          ->out (fn [r]
                  ;; Strip the prefix to retain the relative path
                  (update r :name subs (count prefix)))]
      @(md/chain
        (os/list-objects client (-> conf
                                    (select-keys [:ns :compartment-id :bucket-name])
                                    (assoc :prefix prefix
                                           :fields "name,size")))
        (fn [{:keys [objects]}]
          (->> objects
               (map ->out))))))
  
  (fetch-log [_ sid path]
    ;; TODO Also return object size, so we can tell the client
    ;; FIXME Return nil if file does not exist, instead of throwing an error
    @(md/chain
      (os/get-object client (-> conf
                                (select-keys [:ns :compartment-id :bucket-name])
                                (assoc :object-name (str (sid->prefix sid conf) path))))
      bs/to-input-stream)))

(defmethod l/make-log-retriever :oci [conf]
  (let [oci-conf (:logging conf)
        client (-> (os/make-client oci-conf)
                   (oci/add-inv-interceptor :logging))]
    (->OciBucketLogRetriever client oci-conf)))
