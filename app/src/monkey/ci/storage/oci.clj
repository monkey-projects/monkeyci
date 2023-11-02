(ns monkey.ci.storage.oci
  (:require [clj-commons.byte-streams :as bs]
            [clojure
             [edn :as edn]
             [string :as cs]]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci.storage :as st]
            [monkey.oci.os.core :as os]))

(def get-ns (memoize (fn [client]
                       @(os/get-namespace client {}))))

(def ->edn pr-str)
(def parse-edn edn/read-string)

(defn make-path [sid]
  (cs/join "/" (concat (butlast sid) [(str (last sid) ".edn")])))

(defn- object-args [client conf sid]
  (-> conf
      (select-keys [:compartment-id :bucket-name])
      (assoc :ns (get-ns client)
             :object-name (make-path sid))))

(deftype OciObjectStorage [client conf]
  st/Storage
  (read-obj [this sid]
    (let [args (object-args client conf sid)]
      @(md/chain
        ;; First check if it exists
        (os/head-object client args)
        (fn [v]
          (when v
            (os/get-object client args)))
        (fnil bs/to-string "")
        parse-edn)))
  
  (write-obj [_ sid obj]
    @(md/chain
      (os/put-object
       client
       (-> (object-args client conf sid)
           (assoc :contents (->edn obj)
                  :content-type "application/edn")))
      (constantly sid)))
  
  (delete-obj [this sid]
    @(-> (os/delete-object client (object-args client conf sid))
         (md/chain (constantly true))
         (md/catch (constantly false))))
  
  (obj-exists? [_ sid]
    @(os/head-object client (object-args client conf sid))))

(defn make-oci-storage
  "Creates storage object for OCI buckets, using the configuration specified.
   This configuration should also include OCI credentials."
  [conf]
  (when-not (:region conf)
    (throw (ex-info "Region must be specified" conf)))
  (->OciObjectStorage (os/make-client conf) conf))

(defmethod st/make-storage :oci [{:keys [credentials] :as conf}]
  (make-oci-storage (-> conf
                        (merge credentials)
                        (dissoc :credentials))))
