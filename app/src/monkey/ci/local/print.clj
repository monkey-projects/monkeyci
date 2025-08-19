(ns monkey.ci.local.print
  "Event handlers to print build progress to console"
  (:require [clansi :as cl]
            [clojure
             [string :as cs]
             [walk :as cw]]
            [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [manifold.stream :as ms]
            [monkey.ci
             [console :as co]
             [jobs :as j]
             [version :as v]]
            [monkey.ci.build.core :as bc]))

(def nil-printer (constantly nil))

(defn stream-printer
  "Printer implementation that outputs to a manifold stream"
  [s]
  (fn [msg]
    (ms/put! s msg)
    nil))

(def time-format (jt/formatter "yyyy-MM-dd HH:mm:ss.SSS"))

(defn- apply-styles [msg]
  (letfn [(style [x]
            (if (seqable? x)
              (let [[styles m] x]
                (if (and (seqable? styles) (every? keyword? styles) (string? m))
                  (apply cl/style m styles)
                  (if (every? string? x)
                    (apply str x)
                    x)))
              x))]
    (if (string? msg)
      msg
      (cw/postwalk (fn [x]
                     (if (string? x)
                       x
                       (style x)))
                   msg))))

(def accent [:bright :cyan])
(def warn [:bright :yellow])

(defn console-printer
  "Prints to console"
  [msgs]
  (doseq [m (remove nil? msgs)]
    ;; TODO Ansi coloring
    (println (cl/style (str "[" (jt/format time-format (jt/local-date-time)) "]") :green)
             (apply-styles (:message m)))))

(defn build-init [printer ctx]
  (let [b (get-in ctx [:event :build])]
    (printer [{:message ["Initializing build: " [accent (:build-id b)]]}
              {:message (str "Source: " (:checkout-dir b))}
              {:message (str "Script dir: " (get-in b [:script :script-dir]))}])))

(defn build-start [printer ctx]
  (printer [{:message ["Lib version: " [accent (v/version)]]}]))

(defn- status->msg [{:keys [status] :as b}]
  (if (bc/success? b)
    [[:bright :green] (name status)]
    [[:bright :red] (name status)]))

(defn build-end [printer {e :event}]
  (let [m (:message e)]
    (printer (cond-> [{:message ["Build ended: " [accent (-> e :sid last)]]}
                      {:message ["Status: " (status->msg e)]}]
               m (concat [{:message "Message:"}
                          {:message [warn m]}])))))

(defn script-start [printer ctx]
  (printer [{:message (->> ctx
                           :event
                           :jobs
                           (map j/job-id)
                           (cs/join ", ")
                           (conj [accent])
                           (conj ["Script started with jobs: "]))}]))

(defn script-end [printer ctx]
  (let [m (get-in ctx [:event :message])]
    (printer (cond-> [{:message "Script ended"}]
               m (concat [{:message "Message:"}
                          {:message [warn m]}])))))

(defn job-init [printer ctx]
  (printer [{:message (str "Local work dir: " (get-in ctx [:event :local-dir]))}]))

(defn job-start [printer ctx]
  (printer [{:message ["Job started: " [accent (get-in ctx [:event :job-id])]]}]))

(defn job-end [printer {e :event}]
  (let [o (get-in e [:result :output])]
    (printer (cond-> [{:message ["Job ended: " [accent (:job-id e)]]}
                      {:message ["Status: " (status->msg e)]}]
               (bc/failed? e) (conj {:message "Check the local work dir for job logs."})
               o (concat [{:message "Output:"}
                          {:message [warn o]}])))))

(defn make-routes [{p :printer :as conf}]
  [[:build/initializing [{:handler (partial build-init p)}]]
   [:build/start        [{:handler (partial build-start p)}]]
   [:build/end          [{:handler (partial build-end p)}]]
   [:script/start       [{:handler (partial script-start p)}]]
   [:script/end         [{:handler (partial script-end p)}]]
   [:job/initializing   [{:handler (partial job-init p)}]]
   [:job/start          [{:handler (partial job-start p)}]]
   [:job/end            [{:handler (partial job-end p)}]]])
