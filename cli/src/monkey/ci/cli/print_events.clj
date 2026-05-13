(ns monkey.ci.cli.print-events
  "Event handlers for printing to console"
  (:require [monkey.mailman.core :as mmc]
            [monkey.ci.cli.print :as p]
            [monkey.ci.events.mailman.interceptors :as emi]))

(def build-id (comp last :sid :event))
(def job-id (comp :job-id :event))

(defn set-local-dir [s d]
  (assoc s ::local-dir d))

(def get-local-dir ::local-dir)

(defn set-jobs [s d]
  (assoc s ::jobs d))

(def get-jobs ::jobs)

(defn- print-result [ctx]
  (let [job (job-id ctx)
        p (if job (partial p/print-job-msg job) p/print-timed-msg)]
    (when-let [r (not-empty (get-in ctx [:event :result]))]
      (p "Result:" r))))

;;; --- Interceptors ---

(def save-local-dir
  {:name ::save-local-dir
   :enter (fn [ctx]
            (emi/update-state ctx set-local-dir (get-in ctx [:event :local-dir])))})

(def save-jobs
  {:name ::save-jobs
   :enter (fn [ctx]
            (emi/update-state ctx set-jobs (get-in ctx [:event :jobs])))})

;;; --- Handlers ---

(defn build-init [ctx]
  (p/print-timed-msg "Build initializing:" (build-id ctx)))

(defn build-start [ctx]
  (p/print-timed-msg "Starting child process..."))

(defn build-end [ctx]
  (p/print-timed-msg "Build end:" (build-id ctx))
  (print-result ctx))

(defn script-init [ctx]
  (p/print-timed-msg "Running script in temp dir:" (get-in ctx [:event :script-dir])))

(defn script-start [ctx]
  (p/print-timed-msg "Script started:" (build-id ctx))
  (let [jobs (get-in ctx [:event :jobs])]
    (p/print-timed-msg "Script contains" (count jobs) "jobs:")
    (doseq [j jobs]
      (printf "\t%s - %s job - deps: %s\n" (:id j) (name (:type j)) (:dependencies j)))))

(defn script-end [ctx]
  (p/print-timed-msg "Script completed")
  (print-result ctx))

(defn job-init [ctx]
  (p/print-job-msg (job-id ctx) "Loading job"))

(defn job-start [ctx]
  (let [job (job-id ctx)]
    (p/print-job-msg job "Job started")
    (p/print-job-msg job "Local dir:" (-> ctx (emi/get-state) (get-local-dir)))))

(defn job-end [ctx]
  (p/print-job-msg (job-id ctx) "Job end:" (get-in ctx [:event :status]))
  (print-result ctx))

(defn job-exec [ctx]
  (let [job (job-id ctx)]
    (p/print-job-msg job "Job executed:" (get-in ctx [:event :status]))
    (print-result ctx)))

(defn cmd-start [{:keys [event] :as ctx}]
  (p/print-job-msg (job-id ctx) "Command started:" (:command event)))

(defn cmd-end [{:keys [event] :as ctx}]
  (p/print-job-msg (job-id ctx) "Command ended:" (:command event) ", exit code" (:exit event)))

(defn container-start [ctx]
  (p/print-job-msg (job-id ctx) "Container started"))

(defn container-end [ctx]
  (let [job (job-id ctx)]
    (p/print-job-msg job "Container end")
    (p/print-job-msg job "Status:" (get-in ctx [:event :status]))))

;;; --- Routing config ---

(defn make-routes [config]
  (let [state (atom {})]
    (letfn [(handler [h & [extra-int]]
              {:handler h
               :interceptors (cond-> [(emi/with-state state)]
                               (not-empty extra-int) (concat extra-int))})]
      [[:build/initializing  [(handler build-init)]]
       [:build/start         [(handler build-start)]]
       [:build/end           [(handler build-end)]]
       [:script/initializing [(handler script-init)]]
       [:script/start        [(handler script-start
                                       [save-jobs])]]
       [:script/end          [(handler script-end)]]
       [:job/initializing    [(handler job-init
                                       [save-local-dir])]]
       [:job/start           [(handler job-start)]]
       [:job/executed        [(handler job-exec)]]
       [:job/end             [(handler job-end)]]
       [:command/start       [(handler cmd-start)]]
       [:command/end         [(handler cmd-end)]]
       [:container/start     [(handler container-start)]]
       [:container/end       [(handler container-end)]]])))
