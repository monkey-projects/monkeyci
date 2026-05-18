(ns monkey.ci.cli.print.scrolling
  "Event handlers for printing to console in a scrolling fashion, useful for non-xterms."
  (:require [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.mailman.core :as mmc]
            [monkey.ci.cli.print :as p]
            [monkey.ci.common.jobs :as cj]
            [monkey.ci.events.mailman.interceptors :as emi]))

(def build-id (comp last :sid :event))
(def job-id (comp :job-id :event))

(defn set-local-dir [s d]
  (assoc s ::local-dir d))

(def get-local-dir ::local-dir)

(defn set-jobs [s d]
  (assoc s ::jobs d))

(def get-jobs ::jobs)

(defn get-job
  "Finds job by id in the context state"
  ([ctx id]
   (-> ctx
       (emi/get-state)
       (get-jobs)
       (get id)))
  ([ctx]
   (get-job ctx (job-id ctx))))

(defn get-cmd
  "Retrieves the script command at given index, if the job is a container job"
  ([ctx job-id cmd-idx]
   (some-> (get-job ctx job-id)
           :script
           (nth cmd-idx)))
  ([ctx cmd-idx]
   (get-cmd ctx (job-id ctx) cmd-idx)))

(defn- print-result [ctx]
  (let [job (job-id ctx)
        p (if job (partial p/print-job-msg job) p/print-timed-msg)]
    (when-let [r (not-empty (get-in ctx [:event :result]))]
      (p "Result:" r))))

(defn- status [v]
  (if (= :success v)
    (p/success (name v))
    (p/failure (name v))))

;;; --- Interceptors ---

(def save-local-dir
  {:name ::save-local-dir
   :enter (fn [ctx]
            (emi/update-state ctx set-local-dir (get-in ctx [:event :local-dir])))})

(def save-jobs
  {:name ::save-jobs
   :enter (fn [ctx]
            (emi/update-state ctx set-jobs (->> (get-in ctx [:event :jobs])
                                                (mc/index-by :id))))})

(def handle-error
  {:name ::error
   :error (fn [ctx]
            (log/warn "Got error while handling event" (:event ctx) (:error ctx))
            (dissoc ctx :error))})

;;; --- Handlers ---

(defn build-init [ctx]
  (p/print-timed-msg "Build initializing:" (build-id ctx)))

(defn build-start [ctx]
  (p/print-timed-msg "Starting child process..."))

(defn build-end [ctx]
  (p/print-timed-msg "Build end:" (status (get-in ctx [:event :status])))
  (print-result ctx))

(defn script-init [ctx]
  (p/print-timed-msg "Running script in temp dir:" (get-in ctx [:event :script-dir])))

(defn script-start [ctx]
  (p/print-timed-msg "Script started:" (build-id ctx))
  (let [jobs (get-in ctx [:event :jobs])]
    (p/print-timed-msg "Script contains" (count jobs) "jobs:")
    (->> jobs
         (cj/sort-by-deps)
         (map-indexed
          (fn [idx j]
            (printf "\t%2d. %s (%s)%s\n"
                    (inc idx)
                    (:id j)
                    (name (:type j))
                    (if-let [d (not-empty (:dependencies j))]
                      (str " --> [" (cs/join ", " d) "]")
                      ""))))
         (doall))))

(defn script-end [ctx]
  (p/print-timed-msg "Script completed")
  (print-result ctx))

(defn job-init [ctx]
  (p/print-job-msg (job-id ctx) "Loading job"))

(defn job-start [ctx]
  (let [job-id (job-id ctx)
        job (get-job ctx job-id)]
    (p/print-job-msg job-id "Job started")
    (p/print-job-msg job-id "Local dir:" (-> ctx (emi/get-state) (get-local-dir)))))

(defn job-end [ctx]
  (p/print-job-msg (job-id ctx) "Job end:" (status (get-in ctx [:event :status])))
  (print-result ctx))

(defn job-exec [ctx]
  (p/print-job-msg (job-id ctx) "Job executed:" (status (get-in ctx [:event :status]))))

(defn cmd-start [{:keys [event] :as ctx}]
  (let [cmd (get-cmd ctx (Integer/parseInt (:command event)))]
    (p/print-cmd-start (job-id ctx) cmd)))

(defn cmd-end [{:keys [event] :as ctx}]
  (p/print-job-msg (job-id ctx) "Command ended:" (:command event) ", exit code" (:exit event)))

(defn container-start [ctx]
  (p/print-job-msg (job-id ctx) "Container started"))

(defn container-end [ctx]
  (let [job (job-id ctx)]
    (p/print-job-msg job "Container end")
    (p/print-job-msg job "Status:" (status (get-in ctx [:event :status])))))

;;; --- Routing config ---

(defn make-routes [config]
  (let [state (atom {})]
    (letfn [(handler [h & [extra-int]]
              {:handler h
               :interceptors (cond-> [handle-error
                                      (emi/with-state state)]
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
