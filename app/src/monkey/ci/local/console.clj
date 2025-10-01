(ns monkey.ci.local.console
  "Event handlers for displaying build progress on an xterm, which allows
   for more functionality than a dumb terminal.  The event handlers are
   responsible to add information to the state, which is then used by the
   renderer to periodically write that information to screen."
  (:require [com.stuartsierra.component :as co]
            [java-time.api :as jt]
            [manifold.time :as mt]
            [monkey.ci
             [console :as c]
             [jobs :as j]
             [utils :as u]]
            [monkey.ci.common.jobs :as cj]
            [monkey.ci.events.mailman.interceptors :as mi]
            [monkey.ci.local.common :as lc]))

;;; State management

(def get-build :build)

(defn set-build [state b]
  (assoc state :build b))

(defn update-build [state f & args]
  (apply update state :build f args))

(def get-jobs :jobs)

(defn set-jobs [state jobs]
  (assoc state :jobs jobs))

(defn update-job [state id f & args]
  (let [jobs (get-jobs state)
        m (->> jobs
               (filter (comp (partial = id) j/job-id))
               (first))]
    (set-jobs state (replace {m (apply f m args)} jobs))))

(defmacro with-state [[s ctx] & body]
  `(let [~s (mi/get-state ~ctx)]
     ~@body))

;;; Console rendering

(defn- render-build [{:keys [start-time build-id]}]
  [(str c/reset "Running build: " build-id)
   (str "Started at: " (jt/local-date-time (jt/instant start-time) (jt/zone-id)))])

(defn- render-jobs [jobs]
  ;; TODO 
  [])

(defn render-state
  "Converts state into printable lines, possibly using ansi control codes."
  [{:keys [build jobs]}]
  (cond-> []
    (nil? build) (concat ["Initializing build..."])
    build (concat (render-build build))
    (and build (nil? jobs)) (concat ["Initializing build script..."])
    jobs (concat (render-jobs jobs))))

(defrecord PeriodicalRenderer [state renderer interval]
  co/Lifecycle
  (start [this]
    (assoc this :render-stop (mt/every (or interval 200) #(renderer @state))))
  
  (stop [this]
    (when-let [s (:render-stop this)]
      (s))
    (dissoc this :render-stop)))

(defn console-renderer
  "Creates a renderer function that invokes `src` to generate printable lines,
   which are then sent to the terminal."
  [src]
  (let [cs (atom {:renderer (fn [state]
                              ;; Should src be able to update state?
                              [(src state) state])})]
    (fn [state]
      (swap! cs (fn [s]
                  (-> s
                      (assoc :state state)
                      (c/render-next)))))))

;;; Event handlers

(defn build-init [ctx]
  (with-state [s ctx]
    (set-build s (get-in ctx [:event :build]))))

(defn build-start [ctx]
  (with-state [s ctx]
    (update-build s assoc :start-time (get-in ctx [:event :time]))))

(defn build-end [ctx]
  (with-state [s ctx]
    (-> s
        (update-build assoc :end-time (get-in ctx [:event :time]))
        (update-build merge (select-keys (:event ctx) [:status :message])))))

(defn script-start [ctx]
  (with-state [s ctx]
    (set-jobs s (-> ctx
                    :event
                    :jobs
                    (cj/sort-by-deps)))))

(defn script-end [ctx]
  (with-state [s ctx]
    (update-build s assoc :script-msg (get-in ctx [:event :message]))))

(defn job-init [ctx]
  (with-state [s ctx]
    (update-job s (get-in ctx [:event :job-id])
                assoc :status :initializing)))

(defn job-start [ctx]
  (with-state [s ctx]
    (update-job s (get-in ctx [:event :job-id])
                merge {:status :running
                       :start-time (get-in ctx [:event :time])})))

(defn job-end [{:keys [event] :as ctx}]
  (with-state [s ctx]
    (update-job s (:job-id event)
                merge
                (select-keys event [:status :message])
                {:end-time (:time event)
                 :output (get-in event [:result :output])})))

;;; Interceptors

(def result->state
  {:name ::result->state
   :leave (fn [ctx]
            (-> ctx
                (mi/set-state (:result ctx))
                (dissoc :result)))})

;;; Routes

(defn make-routes [{:keys [state]}]
  (let [i [(mi/with-state state)
           result->state]]
    (-> [[:build/initializing [{:handler build-init}]]
         [:build/start        [{:handler build-start}]]
         [:build/end          [{:handler build-end}]]
         [:script/start       [{:handler script-start}]]
         [:script/end         [{:handler script-end}]]
         [:job/initializing   [{:handler job-init}]]
         [:job/start          [{:handler job-start}]]
         [:job/end            [{:handler job-end}]]]
        (lc/set-interceptors i))))
