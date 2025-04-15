(ns monkey.ci.containers.common
  (:require [babashka.fs :as fs]
            [monkey.ci
             [build :as b]
             [jobs :as j]
             [utils :as u]]
            [monkey.ci.containers :as c]
            [monkey.ci.events.builders :as eb]
            [monkey.ci.events.mailman.interceptors :as emi]
            [monkey.ci.sidecar.config :as sc]))

(def home-dir "/home/monkeyci")
(def checkout-vol "checkout")
(def checkout-dir "/opt/monkeyci/checkout")
(def script-dir "/opt/monkeyci/script")
(def key-dir "/opt/monkeyci/keys")

(def script-vol "scripts")
(def job-script "job.sh")
(def config-vol "config")
(def config-dir "/home/monkeyci/config")
(def job-container-name "job")
(def config-file "config.edn")

(def sidecar-container-name "sidecar")

(def promtail-config-vol "promtail-config")
(def promtail-config-dir "/etc/promtail")
(def promtail-config-file "config.yml")

(defn checkout-subdir
  "Returns the path for `n` as a subdir of the checkout dir"
  [n]
  (str checkout-dir "/" n))

(def work-dir (checkout-subdir "work"))
(def log-dir (checkout-subdir "log"))
(def events-dir (checkout-subdir "events"))
(def start-file (str events-dir "/start"))
(def abort-file (str events-dir "/abort"))
(def event-file (str events-dir "/events.edn"))

(defn base-work-dir
  "Determines the base work dir to use inside the container"
  [build]
  (some->> (b/checkout-dir build)
           (fs/file-name)
           (fs/path work-dir)
           (str)))

(defn job-work-dir
  "The work dir to use for the job in the container.  This is the external job
   work dir, relative to the container checkout dir."
  [job build]
  (let [wd (j/work-dir job)]
    (cond-> (base-work-dir build)
      wd (u/combine wd))))

(def sidecar-cmd
  "Sidecar command list"
  (c/make-cmd
   "-c" (str config-dir "/" config-file)
   "internal"
   "sidecar"
   ;; TODO Move this to config file
   "--events-file" event-file
   "--start-file" start-file
   "--abort-file" abort-file))

(defn make-sidecar-config
  "Creates a configuration map using the runtime, that can then be passed on to the
   sidecar container."
  [{:keys [build job] :as conf}]
  (-> {}
      (sc/set-build (-> build
                        (select-keys (conj b/sid-props :workspace))
                        (assoc :checkout-dir (base-work-dir build))))
      (sc/set-job (-> job
                      (select-keys [:id :save-artifacts :restore-artifacts :caches :dependencies])
                      (assoc :work-dir (job-work-dir job build))))
      (sc/set-events-file event-file)
      (sc/set-start-file start-file)
      (sc/set-abort-file abort-file)
      (sc/set-api (:api conf))))

;; Cpu architectures
(def arch-arm :arm)
(def arch-amd :amd)
(def valid-architectures #{arch-arm arch-amd})

(def arch-credits
  "Credits per cpu for each arch"
  {arch-arm 1
   arch-amd 2})

(defn credit-multiplier
  "Calculates the credit multiplier that needs to be applied for the container
   instance.  This varies depending on the architecture, number of cpu's and 
   amount of memory."
  [arch cpus mem]
  (+ (* cpus
        (get arch-credits arch 1))
     mem))

;;; Interceptors

(def ctx->job-id (comp :job-id :event))
(def ctx->build-sid (comp :sid :event))

(defn get-job-state
  "Retrieves state for the job indicated by `job-id` in the event."
  [ctx]
  (-> (emi/get-state ctx)
      (get-in [::jobs (ctx->build-sid ctx) (ctx->job-id ctx)])))

(defn job-state
  "Applies `f` to job state.  Does not update state."
  [ctx f]
  (f (get-job-state ctx)))

(defn update-job-state
  "Applies `f` to job state, updates state."
  [ctx f & args]
  (apply emi/update-state ctx update-in [::jobs (ctx->build-sid ctx) (ctx->job-id ctx)] f args))

(defn get-container-status [ctx]
  (job-state ctx :container-status))

(def container-ended? 
  (comp some? get-container-status))

(defn sidecar-ended? [ctx]
  (job-state ctx (comp some? :sidecar-status)))

(def set-container-status
  {:name ::set-container-status
   :enter (fn [ctx]
            ;; FIXME container event is inconsistent, status should be in event, not in result
            (update-job-state ctx assoc :container-status (get-in ctx [:event :result :status])))})

(def set-sidecar-status
  {:name ::set-sidecar-status
   :enter (fn [ctx]
            ;; FIXME Status field is also in this event inconsistent
            (update-job-state ctx assoc :sidecar-status (get-in ctx [:event :result :status])))})

(def register-job
  "Interceptor that registers the job referred to in the event in the state, indicating
   that it's being handled by this runner.  Requires state."
  {:name ::register-job
   :enter (fn [ctx]
            (let [job (get-in ctx [:event :job])]
              (cond-> ctx
                job (update-job-state assoc :job job))))})

(def ignore-unknown-job
  "Interceptor that aborts processing if the job indicated in the event is not found in the
   state.  This means the job was handled by another container runner, and should be ignored.
   Requires state and works in conjunction with `register-job`."
  (emi/terminate-when
   ::ignore-unknown-job
   (comp nil? :job get-job-state)))

;;; Common event handlers

(defn- job-executed-evt [{:keys [event] :as ctx}]
  (let [status (or (get-container-status ctx)
                   ;; Normally this is taken care of by interceptor, but it's more resilient
                   (get-in ctx [:event :result :status]))]
    (eb/job-executed-evt (ctx->job-id ctx) (ctx->build-sid ctx) (assoc (:result event) :status status))))

(defn container-end
  "Indicates job script has terminated.  If the sidecar has also ended, the job has been
   executed"
  [ctx]
  (when (sidecar-ended? ctx)
    [(job-executed-evt ctx)]))

(defn sidecar-end
  "Indicates job sidecar has terminated.  If the container script has also ended, the job
   has been executed"
  [ctx]
  (when (container-ended? ctx)
    [(job-executed-evt ctx)]))
