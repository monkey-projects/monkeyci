(ns monkey.ci.local.print
  "Event handlers to print build progress a terminal"
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
             [utils :as u]
             [version :as v]]
            [monkey.ci.build.core :as bc]
            [monkey.ci.common.jobs :as cj]
            [monkey.ci.local.common :as lc]))

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
    (println (cl/style (str "[" (jt/format time-format (jt/local-date-time)) "]") :green)
             (apply-styles (:message m)))))

(defn- status->msg [{:keys [status] :as b}]
  (if (bc/success? b)
    [[:bright :green] (name status)]
    [[:bright :red] (name status)]))

;;; Event handlers

(defn build-init [ctx]
  (let [b (get-in ctx [:event :build])]
    [{:message ["Initializing build: " [accent (:build-id b)]]}
     {:message (str "Source: " (:checkout-dir b))}
     {:message (str "Script dir: " (get-in b [:script :script-dir]))}]))

(defn build-start [ctx]
  [{:message ["Lib version: " [accent (v/version)]]}])

(defn build-end [{e :event}]
  (let [m (:message e)]
    (cond-> [{:message ["Build ended: " [accent (-> e :sid last)]]}
             {:message ["Status: " (status->msg e)]}]
      m (concat [{:message "Message:"}
                 {:message [warn m]}]))))

(defn script-start [ctx]
  [{:message (->> ctx
                  :event
                  :jobs
                  (cj/sort-by-deps)
                  (map j/job-id)
                  (cs/join ", ")
                  (conj [accent])
                  (conj ["Script started with jobs: "]))}])

(defn script-end [ctx]
  (let [m (get-in ctx [:event :message])]
    (cond-> [{:message "Script ended"}]
      m (concat [{:message "Message:"}
                 {:message [warn m]}]))))

(defn job-init [ctx]
  (when-let [dir (get-in ctx [:event :local-dir])]
    [{:message (str "Local work dir: " dir)}]))

(defn job-start [ctx]
  [{:message ["Job started: " [accent (get-in ctx [:event :job-id])]]}])

(defn job-end [{e :event}]
  (let [o (get-in e [:result :output])]
    (cond-> [{:message ["Job ended: " [accent (:job-id e)]]}
             {:message ["Status: " (status->msg e)]}]
      (bc/failed? e) (conj {:message "Check the local work dir for job logs."})
      o (concat [{:message "Output:"}
                 {:message [warn o]}]))))

;;; Interceptors

(defn printer-interceptor [printer]
  "Invokes the printer with the result of the handler.  Replaces the
   result with the return value of the printer"
  {:name ::printer
   :leave (fn [ctx]
            (update ctx :result printer))})

;;; Routes

(defn make-routes [{:keys [printer]}]
  (let [i [(printer-interceptor printer)]]
    (-> [[:build/initializing [{:handler build-init}]]
         [:build/start        [{:handler build-start}]]
         [:build/end          [{:handler build-end}]]
         [:script/start       [{:handler script-start}]]
         [:script/end         [{:handler script-end}]]
         [:job/initializing   [{:handler job-init}]]
         [:job/start          [{:handler job-start}]]
         [:job/end            [{:handler job-end}]]]
        ;; Add same interceptors to all handlers
        (lc/set-interceptors i))))
