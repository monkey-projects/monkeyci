(ns monkey.ci.logging.oci
  (:require [clj-commons.byte-streams :as bs]
            [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [build :as b]
             [logging :as l]
             [oci :as oci]
             [sid :as sid]]
            [monkey.oci.os.core :as os]))

(defn- ensure-cleanup
  "Registers a shutdown hook to ensure the deferred is being completed, even if the
   system shuts down.  The shutdown hook is removed on completion.  If we don't do
   this, the multipart streams don't get committed when the vm shuts down in the
   process."
  [d]
  (let [shutdown? (atom false)
        t (Thread. (fn []
                     (reset! shutdown? true)
                     (log/debug "Waiting for upload to complete...")
                     (deref d)
                     (log/debug "Upload completed")))
        remove-hook (fn [& _]
                      (when-not @shutdown?
                        (try 
                          (.removeShutdownHook (Runtime/getRuntime) t)
                          (catch Exception _
                            (log/warn "Unable to remove shutdown hook, process is probably already shutting down.")))))]
    (if (md/deferred? d)
      (do
        (.addShutdownHook (Runtime/getRuntime) t)
        (md/finally d remove-hook))
      d)))

(defn sid->path [{:keys [prefix]} path sid]
  (->> (concat [prefix] sid path)
       (remove nil?)
       (cs/join "/")))

(deftype OciBucketLogger [conf build path]
  l/LogCapturer
  (log-output [_]
    :stream)

  (handle-stream [_ in]
    (let [sid (b/sid build)
          ;; Since the configured path already includes the build id,
          ;; we only use repo id to build the path
          on (sid->path conf path (sid/sid->repo-sid sid))]
      (-> (oci/stream-to-bucket (assoc conf :object-name on) in)
          (ensure-cleanup)))))

(defmethod l/make-logger :oci [conf]
  (fn [build path]
    (-> conf
        :logging
        (->OciBucketLogger build path))))

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
