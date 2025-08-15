(ns monkey.ci.local.print
  "Event handlers to print build progress to console"
  (:require [java-time.api :as jt]
            [manifold.stream :as ms]
            [monkey.ci
             [console :as co]
             [version :as v]]))

(def nil-printer (constantly nil))

(defn stream-printer
  "Printer implementation that outputs to a manifold stream"
  [s]
  (fn [msg]
    (ms/put! s msg)
    nil))

(defn console-printer
  "Prints to console"
  [msgs]
  (doseq [m (remove nil? msgs)]
    ;; TODO Ansi coloring
    (println (co/accent (str "[" (jt/format :iso-local-date-time (jt/local-date-time)) "]"))
             (:message m))))

(defn build-init [printer ctx]
  (let [b (get-in ctx [:event :build])]
    (printer [{:message (str "Initializing build: " (:build-id b))}
              {:message (str "Source: " (:checkout-dir b))}
              {:message (str "Script dir: " (get-in b [:script :script-dir]))}])))

(defn build-start [printer ctx]
  (printer [{:message (str "Build started: " (-> ctx :event :sid last))}
            {:message (str "Lib version: " (v/version))}]))

(defn build-end [printer {e :event}]
  (printer [{:message (str "Build ended: " (-> e :sid last))}
            {:message (str "Status: " (name (:status e)))}
            (when (:message e)
              {:message (str "Message: " (:message e))})]))

(defn script-start [printer _]
  (printer [{:message "Script started, loading and running jobs..."}]))

(defn script-end [printer ctx]
  (printer [{:message "Script ended"}
            (when-let [m (get-in ctx [:event :message])]
              {:message (str "Message: " m)})]))

(defn job-start [printer ctx]
  (printer [{:message (str "Job started: " (get-in ctx [:event :job-id]))}]))

(defn job-end [printer {e :event}]
  (printer [{:message (str "Job ended: " (:job-id e))}
            {:message (str "Status: " (name (:status e)))}
            (when-let [o (get-in e [:result :output])]
              {:message (str "Output: " o)})]))

(defn make-routes [{p :printer :as conf}]
  [[:build/initializing [{:handler (partial build-init p)}]]
   [:build/start        [{:handler (partial build-start p)}]]
   [:build/end          [{:handler (partial build-end p)}]]
   [:script/start       [{:handler (partial script-start p)}]]
   [:script/end         [{:handler (partial script-end p)}]]
   [:job/start          [{:handler (partial job-start p)}]]
   [:job/end            [{:handler (partial job-end p)}]]])
