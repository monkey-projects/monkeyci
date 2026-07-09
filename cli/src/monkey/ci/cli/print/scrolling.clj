(ns monkey.ci.cli.print.scrolling
  "Event handlers for printing to console in a scrolling fashion, useful for non-xterms."
  (:require [clojure.string :as cs]
            [clojure.tools.logging :as log]
            [medley.core :as mc]
            [monkey.mailman.core :as mmc]
            [monkey.ci.cli
             [config :as conf]
             [print :as p]]
            [monkey.ci.common.jobs :as cj]
            [monkey.ci.events.mailman.interceptors :as emi]))

(def build-id (comp last :sid :event))
(def job-id (comp :job-id :event))

(defn set-jobs [s d]
  (assoc s ::jobs d))

(def get-jobs ::jobs)

(defn set-local-dir [s job d]
  (assoc-in s [::jobs job :local-dir] d))

(defn get-local-dir [s job]
  (get-in s [::jobs job :local-dir]))

(defn get-job
  "Finds job by id in the context state"
  ([ctx id]
   (-> ctx
       (emi/get-state)
       (get-jobs)
       (get id)))
  ([ctx]
   (get-job ctx (job-id ctx))))

(defn pad-job-id
  "Pads the job id to max length of all job ids"
  ([ctx id]
   (let [m (->> (emi/get-state ctx)
                (get-jobs)
                (keys)
                (map count)
                (apply max))]
     (apply str id (repeat (- m (count id)) " "))))
  ([ctx]
   (pad-job-id ctx (job-id ctx))))

(defn get-cmd
  "Retrieves the script command at given index, if the job is a container job"
  ([ctx job-id cmd-idx]
   (some-> (get-job ctx job-id)
           :script
           (nth cmd-idx)))
  ([ctx cmd-idx]
   (get-cmd ctx (job-id ctx) cmd-idx)))

(defn- ->int [x]
  (cond
    (number? x) x
    (string? x) (Integer/parseInt x)
    :else 0))

(defn- print-test-results [ctx]
  (when-let [r (not-empty (get-in ctx [:event :result :monkey.ci/tests]))]
    (let [s (->> r
                 (map #(select-keys % [:tests :errors :failures]))
                 (map (partial mc/map-vals ->int))
                 (apply merge-with +))]
      (p/print-job-msg (pad-job-id ctx)
                       "Test results:" (p/em (format "%d tests, %d errors, %d failures."
                                                    (:tests s) (:errors s) (:failures s)))))))

(defn- print-result [ctx]
  (let [job (job-id ctx)
        p (if job (partial p/print-job-msg (pad-job-id ctx job)) p/print-timed-msg)]
    (when-let [m (not-empty (or (get-in ctx [:event :result :message])
                                (get-in ctx [:event :message])))]
      (p "Message:" m))
    (print-test-results ctx)))

(defn- status [v]
  (if (= :success v)
    (p/success (name v))
    (p/failure (name v))))

;;; --- Interceptors ---

(def save-local-dir
  "Stores the local dir for the job in state"
  {:name ::save-local-dir
   :enter (fn [ctx]
            (emi/update-state ctx set-local-dir (job-id ctx) (get-in ctx [:event :local-dir])))})

(def save-jobs
  {:name ::save-jobs
   :enter (fn [ctx]
            (emi/update-state ctx set-jobs (->> (get-in ctx [:event :jobs])
                                                (mc/index-by :id))))})

(def handle-error
  {:name ::error
   :error (fn [ctx]
            (println "Got error while handling event" (:event ctx) (:error ctx))
            (dissoc ctx :error))})

;;; --- Handlers ---

(defn build-init [ctx]
  (p/print-timed-msg "Build initializing:" (build-id ctx)))

(defn build-start [ctx]
  (p/print-timed-msg "Starting child process..."))

(defn build-end [conf ctx]
  (let [s (get-in ctx [:event :status])]
    (p/print-timed-msg "Build ended with status:" (status s))
    (print-result ctx)
    (when (not= :success s)
      (p/print-timed-msg "Build failed, keeping the work dir for inspection:"
                         (str (conf/get-work-dir conf))))))

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
  (p/print-job-msg (pad-job-id ctx) "Loading job"))

(defn job-start [ctx]
  (let [job-id (job-id ctx)
        p (pad-job-id ctx job-id)
        job (get-job ctx job-id)]
    (p/print-job-msg p "Job started")
    (when-let [ld (-> ctx (emi/get-state) (get-local-dir job-id))]
      (p/print-job-msg p "Local dir:" ld))))

(defn job-end [ctx]
  (if (= :success (get-in ctx [:event :status]))
    (p/print-job-msg (pad-job-id ctx) (p/success "Job succeeded!"))
    (p/print-job-msg (pad-job-id ctx)
                     (p/failure "Job failed.")
                     "You can review the logs in" (get-local-dir (emi/get-state ctx) (job-id ctx))))
  (print-result ctx))

(defn cmd-start [{:keys [event] :as ctx}]
  (let [cmd (get-cmd ctx (Integer/parseInt (:command event)))]
    (p/print-cmd-start (pad-job-id ctx) cmd)))

(defn cmd-end [{:keys [event] :as ctx}]
  (p/print-job-msg (pad-job-id ctx) "Command ended, exit code" (:exit event)))

(defn container-start [ctx]
  (p/print-job-msg (pad-job-id ctx) "Container started:" (p/em (:container/image (get-job ctx)))))

(defn container-end [ctx]
  (p/print-job-msg (pad-job-id ctx) "Container exited with status" (status (get-in ctx [:event :status]))))

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
       [:build/end           [(handler (partial build-end config))]]
       [:script/initializing [(handler script-init)]]
       [:script/start        [(handler script-start
                                       [save-jobs])]]
       [:script/end          [(handler script-end)]]
       [:job/initializing    [(handler job-init
                                       [save-local-dir])]]
       [:job/start           [(handler job-start)]]
       [:job/end             [(handler job-end)]]
       [:command/start       [(handler cmd-start)]]
       [:command/end         [(handler cmd-end)]]
       [:container/start     [(handler container-start)]]
       [:container/end       [(handler container-end)]]])))
