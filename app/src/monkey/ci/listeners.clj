(ns monkey.ci.listeners
  (:require [clojure.core.async :as ca]
            [clojure.tools.logging :as log]
            [monkey.ci.storage :as st]))

(defn- update-pipeline [ctx evt f & args]
  (apply st/patch-build-results
         (:storage ctx)
         (:sid evt)
         update-in [:pipelines (get-in evt [:pipeline :index])] f args))

(defn pipeline-started [ctx evt]
  (update-pipeline ctx evt merge {:start-time (:time evt)
                                  :name (get-in evt [:pipeline :name])}))

(defn pipeline-completed [ctx evt]
  (update-pipeline ctx evt merge {:end-time (:time evt)
                                  :status (:status evt)}))

(defn- update-step [ctx evt f & args]
  (apply st/patch-build-results
         (:storage ctx)
         (:sid evt)
         update-in [:pipelines (get-in evt [:pipeline :index]) :steps (:index evt)] f args))

(defn step-started [ctx evt]
  (update-step ctx evt merge {:start-time (:time evt)
                              :name (:name evt)}))

(defn step-completed [ctx evt]
  (update-step ctx evt merge {:end-time (:time evt)
                              :status (:status evt)}))

(defn save-build-result
  "Handles a `build/completed` event to store the result."
  [ctx evt]
  (let [r (select-keys evt [:exit :result])]
    (log/debug "Saving build result:" r)
    (st/patch-build-results (:storage ctx)
                            (get-in evt [:build :sid])
                            merge r)))

(defn build-update-handler
  "Handles a build update event.  Because many events may come in close proximity,
   we need to queue them to avoid losing data.  This handler posts the received
   events to another mult, grouped by build sid, where they are processed sequentially."
  [ctx]
  (let [handlers {:pipeline/start  pipeline-started
                  :pipeline/end    pipeline-completed
                  :step/start      step-started
                  :step/end        step-completed
                  :build/completed save-build-result
                  }
        ch (ca/chan 10)
        p nil #_(ca/pub ch :type)
        ch-per-build (atom {})
        dispatch-sub (fn [s dest]
                       (ca/go-loop [v (ca/<! s)]
                         (when v
                           (log/debug "Handling:" v)
                           (try
                             (dest ctx v)
                             (catch Exception ex
                               ;; TODO Handle this better
                               (log/error "Unable to handle event" ex)))
                           (recur (ca/<! s)))))
        ensure-sub (fn [sid]
                     (when-not (some? (get ch-per-build sid))
                       (log/debug "Registering sub for sid" sid)
                       (let [h (reduce-kv
                                (fn [r t v]
                                  (let [ch (ca/chan)]
                                    ;; Read the sub channel
                                    (dispatch-sub ch v)
                                    (ca/sub p t ch)
                                    (assoc r t ch)))
                                {}
                                handlers)]
                         (swap! ch-per-build assoc sid h))))]
    ;; Naive implementation: process them in sequence.  This does not look 
    ;; to the sid for optimization, so it could be faster.
    (dispatch-sub ch (fn [ctx evt]
                       (when-let [h (get handlers (:type evt))]
                         (h ctx evt))))
    (fn [evt]
      ;; FIXME This doesn't work well, it tends to register too many subs
      #_(ensure-sub (get-in evt [:build :sid]))
      #_(ca/go (ca/>! ch evt))
      (ca/put! ch evt))))
