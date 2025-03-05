(ns monkey.ci.oci
  "Oracle cloud specific functionality"
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [manifold.deferred :as md]
            [martian.interceptors :as mi]
            [medley.core :as mc]
            [monkey.ci
             [build :as b]
             [config :as config]
             [retry :as retry]
             [time :as t]
             [utils :as u]]
            [monkey.ci.common.preds :as cp]
            [monkey.oci.container-instance.core :as ci]
            [monkey.oci.os
             [martian :as os]
             [stream :as s]]
            [taoensso.telemere :as tt]))

;; Cpu architectures
(def arch-arm :arm)
(def arch-amd :amd)
(def valid-architectures #{arch-arm arch-amd})

(def arch-shapes
  "Architectures mapped to OCI shapes"
  {arch-arm
   {:shape "CI.Standard.A1.Flex"
    :credits 1}
   arch-amd
   {:shape "CI.Standard.E4.Flex"
    :credits 2}})

(def default-arch arch-arm)

(defn find-shape [shape-name]
  (->> arch-shapes
       (vals)
       (filter (comp (partial = shape-name) :shape))
       (first)))

(defn invocation-interceptor
  "A Martian interceptor that dispatches telemere events for each invocation.  Useful
   for metrics to know how many api calls were done."
  [kind]
  {:name ::invocation-interceptor
   :enter (fn [ctx]
            ;; TODO More properties
            (tt/event! :oci/invocation
                       {:data {:kind kind}
                        :level :info})
            ctx)})

(defn add-interceptor
  "Adds the given interceptor before all other inceptors of the Martian context"
  [ctx i]
  (let [id (-> ctx :interceptors first :name)]
    (update ctx :interceptors mi/inject i :before id)))

(defn add-inv-interceptor [ctx kind]
  (add-interceptor ctx (invocation-interceptor kind)))

(defn too-many-requests? [r]
  (= 429 (:status r)))

(defn with-retry
  "Invokes `f` with async retry"
  [f]
  (retry/async-retry f {:max-retries 10
                        :retry-if too-many-requests?
                        :backoff (retry/with-max (retry/exponential-delay 1000) 60000)}))

(defn retry-fn [f]
  (fn [& args]
    (with-retry #(apply f args))))

(defn stream-to-bucket
  "Pipes an input stream to a bucket object using multipart uploads.
   Returns a deferred that will resolve when the upload completes.
   That is, when the input stream closes, or an error occurs."
  [conf ^java.io.InputStream in]
  (log/debug "Piping stream to bucket" (:bucket-name conf) "at" (:object-name conf))
  (log/trace "Piping stream to bucket using config" conf)
  (-> conf
      (os/make-context)
      (add-inv-interceptor :stream)
      (s/input-stream->multipart (assoc conf :input-stream in))))

(def terminated? #{"INACTIVE" "DELETED" "FAILED"})

(defn get-full-instance-details
  "Retrieves full container instance details by retrieving the container instance 
   information, and fetching container details as well."
  [client id]
  (log/trace "Retrieving container instance details for" id)
  (md/chain
   (with-retry #(ci/get-container-instance client {:instance-id id}))
   ;; TODO Handle error responses
   (fn [{:keys [status body] :as r}]
     (if (>= status 400)
       (do
         (log/warn "Got error response:" status ", message:" (:message body))
         [r []])
       ;; Fetch details for all containers
       (->> body
            :containers
            (map #(select-keys % [:container-id]))
            (map (partial ci/get-container client))
            (apply md/zip)
            (md/zip r))))
   (fn [[r containers]]
     ;; Add the exit codes for all containers to the result
     (log/trace "Container details:" containers)
     (let [by-id (->> containers
                      (map :body) ; TODO Check for error response
                      (group-by :id)
                      (mc/map-vals first))
           add-exit-code (fn [c]
                           (merge c (-> (get by-id (:container-id c))
                                        (dissoc :id))))]
       (mc/update-existing-in r [:body :containers] (partial map add-exit-code))))))

(defn list-active-instances
  "Lists all active container instances for the given compartment id"
  [client cid]
  (ci/list-container-instances client {:compartment-id cid :lifecycle-state "ACTIVE"}))

(def home-dir "/home/monkeyci")
(def checkout-vol "checkout")
(def checkout-dir "/opt/monkeyci/checkout")
(def script-dir "/opt/monkeyci/script")
(def key-dir "/opt/monkeyci/keys")

(defn- container-config [conf]
  ;; The image url must point to a container running monkeyci cli
  {:image-url (str (:image-url conf) ":" (:image-tag conf))
   :volume-mounts [{:mount-path checkout-dir
                    :is-read-only false
                    :volume-name checkout-vol}]})

(defn- pick-ad
  "Either returns the configured AD, or picks one from the list if multiple specified."
  [{ads :availability-domains ad :availability-domain}]
  (or ad (when ads (nth (vec ads) (rand-int (count ads))))))

(def default-cpu-count 1)
(def default-memory-gb 2)

(defn instance-config
  "Generates a skeleton instance configuration, generated from the oci configuration."
  [conf]
  (-> conf
      (select-keys [:compartment-id :image-pull-secrets :vnics :freeform-tags])
      (assoc :container-restart-policy "NEVER"
             :shape (or (:shape conf)
                        (get-in arch-shapes [default-arch :shape])) ; Use ARM shape, it's cheaper
             :shape-config (merge
                            {:ocpus default-cpu-count
                             :memory-in-g-bs default-memory-gb}
                            (:shape-config conf))
             :availability-domain (pick-ad conf)
             :volumes [{:name checkout-vol
                        :volume-type "EMPTYDIR"
                        :backing-store "EPHEMERAL_STORAGE"}]
             :containers [(container-config conf)])))

(defn sid->tags [sid]
  (->> sid
       (remove nil?)
       (zipmap ["customer-id" "repo-id"])))

(defn find-mount
  "Finds mount with given volume name in the container"
  [c n]
  (mc/find-first (cp/prop-pred :volume-name n)
                 (:volume-mounts c)))

(defn find-volume
  "Finds volume with given name in the container instance"
  [ci n]
  (mc/find-first (cp/prop-pred :name n)
                 (:volumes ci)))

(defn make-config-vol [name configs]
  {:name name
   :volume-type "CONFIGFILE"
   :configs configs})

(defn config-entry
  "Creates an entry config for a volume, where the contents are base64 encoded."
  [n v]
  {:file-name n
   :data (u/->base64 v)})

(defn checkout-subdir
  "Returns the path for `n` as a subdir of the checkout dir"
  [n]
  (str checkout-dir "/" n))

(def work-dir (checkout-subdir "work"))

(defn base-work-dir
  "Determines the base work dir to use inside the container"
  [build]
  (some->> (b/checkout-dir build)
           (fs/file-name)
           (fs/path work-dir)
           (str)))

(defn credit-multiplier
  "Calculates the credit multiplier that needs to be applied for the container
   instance.  This varies depending on the architecture, number of cpu's and 
   amount of memory."
  ([arch cpus mem]
   (+ (* cpus
         (get-in arch-shapes [arch :credits] 1))
      mem))
  ([{:keys [shape shape-config]}]
   (let [a (find-shape shape)]
     (+ (* (:credits a) (:ocpus shape-config))
        (:memory-in-g-bs shape-config)))))

(defn delete-stale-instances [client cid]
  ;; Timeout is the max time a script may run, with a margin of one minute
  (let [timeout (jt/instant (- (t/now) config/max-script-timeout 60000))]
    (letfn [(stale? [x]
              (jt/before? (jt/instant (:time-created x)) timeout))
            (build? [x]
              (let [props ((juxt :customer-id :repo-id) (:freeform-tags x))]
                (and (not-empty props) (every? some? props))))
            (check-errors [resp]
              (when (>= (:status resp) 400)
                (throw (ex-info "Got error response from OCI" resp)))
              resp)
            (delete-instance [ci]
              (log/warn "Deleting stale container instance:" (:id ci))
              @(md/chain
                (ci/delete-container-instance client {:instance-id (:id ci)})
                check-errors
                (constantly ci)))
            (->out [ci]
              (-> (select-keys (:freeform-tags ci) [:customer-id :repo-id])
                  (assoc :build-id (:display-name ci)
                         :instance-id (:id ci))))]
      (->> @(ci/list-container-instances client {:compartment-id cid
                                                 :lifecycle-state "ACTIVE"})
           (check-errors)
           :body
           :items
           (filter (every-pred build? stale?))
           (map delete-instance)
           (mapv ->out)))))

;;; Interceptors

(def get-ci-config ::ci-config)

(defn set-ci-config [ctx bi]
  (assoc ctx ::ci-config bi))

(def get-ci-response ::ci-response)

(defn set-ci-response [ctx bi]
  (assoc ctx ::ci-response bi))

(def get-ci-id
  "Retrieves container instance id from the context"
  ::ci-id)

(defn set-ci-id [ctx bi]
  (assoc ctx ::ci-id bi))

(defn- create-instance [client config]
  (log/debug "Creating container instance using config" config)
  (with-retry
    #(ci/create-container-instance client {:container-instance config})))

(defn- log-ci-error [{:keys [status body] :as resp}]
  (when (or (nil? status) (>= status 400))
    (log/error "Failed to create container instance:" resp))
  resp)

(defn start-ci-interceptor
  "Interceptor that starts container instance using the config specified in the context"
  [client]
  {:name ::start-ci
   :enter (fn [ctx]
            ;; Start container instance, put result back in the context
            ;; TODO Async processing?
            (->> @(create-instance client (get-ci-config ctx))
                 (log-ci-error)
                 (set-ci-response ctx)))})

(defn delete-ci-interceptor
  "Deletes the container instance associated with the build"
  [client]
  {:name ::delete-ci
   :leave (fn [ctx]
            (if-let [instance-id (get-ci-id ctx)]
              @(md/chain
                (with-retry #(ci/delete-container-instance client {:instance-id instance-id}))
                (fn [res]
                  (if (< (:status res) 400)
                    (log/info "Container instance" instance-id "has been deleted")
                    (log/warn "Unable to delete container instance" instance-id ", got status" (:status res)))))
              (log/warn "Unable to delete container instance, no instance id in context"))
            ctx)})
