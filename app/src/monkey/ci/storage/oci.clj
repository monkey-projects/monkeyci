(ns monkey.ci.storage.oci
  (:require [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [manifold.deferred :as md]
            [monkey.ci
             [edn :as edn]
             [protocols :as p]
             [sid :as sid]
             [storage :as st]
             [utils :as u]]
            [monkey.oci.os.core :as os]))

(def get-ns (memoize (fn [client]
                       @(os/get-namespace client {}))))

(def ext ".edn")
(def delim sid/delim)

(defn make-path [sid]
  (cs/join delim (concat (butlast sid) [(str (last sid) ext)])))

(defn- basic-args [client conf]
  (-> conf
      (select-keys [:compartment-id :bucket-name])
      (assoc :ns (get-ns client))))

(defn- object-args [client conf sid]
  (assoc (basic-args client conf)
         :object-name (make-path sid)))

(defn- strip-ext [s]
  (cond-> s
    (cs/ends-with? s ext) (subs 0 (- (count s) (count ext)))))

(defn- strip-prefix [prefix s]
  (subs s (count prefix)))

(defrecord OciObjectStorage [client conf]
  p/Storage
  (read-obj [this sid]
    (let [args (object-args client conf sid)]
      @(md/chain
        ;; First check if it exists
        (os/head-object client args)
        (fn [v]
          (when v
            (os/get-object client args)))
        (u/or-nil edn/edn->))))
  
  (write-obj [_ sid obj]
    @(md/chain
      (os/put-object
       client
       (-> (object-args client conf sid)
           (assoc :contents (edn/->edn obj)
                  :content-type "application/edn")))
      (constantly sid)))
  
  (delete-obj [this sid]
    @(-> (os/delete-object client (object-args client conf sid))
         (md/chain (constantly true))
         (md/catch (constantly false))))
  
  (obj-exists? [_ sid]
    @(os/head-object client (object-args client conf sid)))

  (list-obj [_ sid]
    (let [prefix (str (cs/join delim sid) delim)]
      @(md/chain
        ;; TODO Pagination in case of large resultset
        (os/list-objects client (-> (basic-args client conf)
                                    (assoc :prefix prefix
                                           :delimiter delim)))
        (fn [{:keys [objects prefixes]}]
          (concat (map (comp strip-ext
                             (partial strip-prefix prefix)
                             :name)
                       objects)
                  (map (comp #(subs % 0 (dec (count %)))
                             (partial strip-prefix prefix))
                       prefixes)))))))

(defn make-oci-storage
  "Creates storage object for OCI buckets, using the configuration specified.
   This configuration should also include OCI credentials."
  [conf]
  (when-not (:region conf)
    (throw (ex-info "Region must be specified" conf)))
  (-> (->OciObjectStorage (os/make-client conf) conf)
      (assoc :cached? true)))

(defmethod st/make-storage :oci [conf]
  (log/debug "Creating oci storage with config:" (:storage conf))
  (-> conf
      :storage
      (make-oci-storage)))
