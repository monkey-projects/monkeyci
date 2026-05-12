(ns monkey.ci.cli.print-events
  "Event handlers for printing to console"
  (:require [monkey.mailman.core :as mmc]
            [monkey.ci.cli.print :as p]))

(def build-id (comp last :sid :event))
(def job-id (comp :job-id :event))

(defn build-init [ctx]
  (println "Build initializing:" (build-id ctx)))

(defn build-start [ctx]
  (println "Build starting:" (build-id ctx)))

(defn build-end [ctx]
  (println "Build end:" (build-id ctx))
  (println "Result:" (get-in ctx [:event :result])))

(defn script-init [ctx]
  (println "Loading script:" (build-id ctx)))

(defn script-start [ctx]
  (println "Script started:" (build-id ctx)))

(defn script-end [ctx]
  (println "Script end:" (build-id ctx))
  (println "Result:" (get-in ctx [:event :result])))

(defn job-init [ctx]
  (println "Loading job:" (job-id ctx)))

(defn job-start [ctx]
  (println "Job started:" (job-id ctx)))

(defn job-end [ctx]
  (println "Job end:" (job-id ctx))
  (println "Status:" (get-in ctx [:event :status]))
  (println "Result:" (get-in ctx [:event :result])))

(defn job-exec [ctx]
  (println "Job executed:" (job-id ctx))
  (println "Status:" (get-in ctx [:event :status]))
  (println "Result:" (get-in ctx [:event :result])))

(defn container-start [ctx]
  (println "Container started:" (job-id ctx)))

(defn container-end [ctx]
  (println "Container end:" (job-id ctx))
  (println "Status:" (get-in ctx [:event :status])))

(defn make-routes [config]
  [[:build/initializing [{:handler build-init}]]
   [:build/start [{:handler build-start}]]
   [:build/end [{:handler build-end}]]
   [:script/initializing [{:handler script-init}]]
   [:script/start [{:handler script-start}]]
   [:script/end [{:handler script-end}]]
   [:job/initializing [{:handler job-init}]]
   [:job/start [{:handler job-start}]]
   [:job/executed [{:handler job-exec}]]
   [:job/end [{:handler job-end}]]
   [:container/start [{:handler container-start}]]
   [:container/end [{:handler container-end}]]])
