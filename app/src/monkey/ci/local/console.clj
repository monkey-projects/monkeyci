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

(def color-white   (c/color-256 15))
(def color-yellow  (c/color-256 11))
(def color-success (c/color-256 118))
(def color-failure (c/color-256 160))

(defn- with-color [c msg]
  (str c msg c/reset))

(def in-white  (partial with-color color-white))
(def in-yellow (partial with-color color-yellow))

(defn- render-build [{:keys [start-time build-id]}]
  [(str c/reset (c/erase-line) "Running build: " (in-yellow build-id))
   (str "Started at:    " (in-yellow (jt/local-date-time (jt/instant start-time) (jt/zone-id))))])

(def complete? (comp #{:success :failure :error} :status))

(defn- render-jobs [i jobs]
  (let [w (->> jobs
               (map (comp count j/job-id))
               (apply max))
        perc 0.5
        inv-speed 20 ; Inverse speed, the lower, the faster the animation runs
        s (if i (- (mod (float (/ i inv-speed)) (+ 1 perc)) perc) 0)]
    (mapv (fn [job]
            (str (c/erase-line)
                 (in-white (format (format "%%%ds" w) (j/job-id job)))
                 " [ "
                 (with-color
                   (c/color-256 75)
                   (c/progress-bar (cond-> {:width (int (* (or (c/cols) 70) 0.75))}
                                     (complete? job)
                                     (assoc :value 1)
                                     (not (complete? job))
                                     (assoc :value (min 1 (+ perc s))
                                            :start (max 0 s)))))
                 " ] "
                 (in-yellow (name (:status job)))))
          jobs)))

(defn render-state
  "Converts state into printable lines, possibly using ansi control codes."
  [{:keys [build jobs i]}]
  (cond-> []
    (nil? build) (concat ["Initializing build..."])
    build (concat (render-build build))
    (and build (nil? jobs)) (concat ["Initializing build script..."])
    jobs (concat (render-jobs i jobs))
    (= :success (:status build)) (concat [color-success "Build completed succesfully!" c/reset])
    (= :failure (:status build)) (concat [color-failure "Build failed." c/reset])))

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
                  (let [i (inc (get s :i 0))]
                    (-> s
                        (assoc :state state :i i)                                                
                        ;; Iteration useful for animations
                        (assoc-in [:state :i] i)
                        (c/render-next)
                        ;; Save iteration for next
                        (assoc :i i))))))))

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
