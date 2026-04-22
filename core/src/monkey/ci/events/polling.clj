(ns monkey.ci.events.polling
  "Functions for event polling.  This is different from event listeners,
   because pollers can decide for themselves when they want to fetch the
   next event.  This is useful for backpressure, when there is limited
   capacity to handle events."
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as co]
            [monkey.ci.events.mailman :as em]
            [monkey.mailman.core :as mmc]))

(defn- repost-results [mailman res]
  (->> res
       (map :result)
       (flatten)
       (remove nil?)
       (em/post-events mailman)))

(defn poll-next [{:keys [mailman mailman-out event-types]} router max-reached?]
  (try
    (log/trace "Max reached?" (max-reached?))
    (when-not (max-reached?)
      (log/trace "Polling for next event")
      (when-let [[evt] (mmc/poll-events (:broker mailman) 1)]
        (when (event-types (:type evt))
          (log/trace "Polled next event:" evt)
          (repost-results (or mailman-out mailman) (router evt)))))
    (catch Exception ex
      (log/warn "Got error while polling:" (ex-message ex) ex))))

(defn poll-loop
  "Starts a poll loop that takes events from an event receiver as long as
   the max number of simultaneous builds has not been reached.  When a 
   build finishes, a new event is taken from the queue.  This loop should
   only receive events as configured in the `event-types` property.  The
   `running?` atom allows to stop the loop (e.g. on shutdown), whereas
   the `max-reached?` function returns `true` whenever maximum capacity is
   reached.  As long as this is the case, no new events are polled."
  [{:keys [poll-interval] :or {poll-interval 1000} :as conf} router running? max-reached?]
  (while @running?
    (when-not (poll-next conf router max-reached?)
      (Thread/sleep poll-interval)))
  (log/debug "Poll loop terminated"))

(defrecord PollLoop [config max-reached? running? mailman mailman-out routes]
  co/Lifecycle
  (start [this]
    (log/info "Starting poll loop with config" config)
    (reset! running? true)
    (assoc this :future (future
                          (poll-loop (assoc config
                                            :mailman mailman
                                            :mailman-out mailman-out)
                                     (mmc/router (:routes routes))
                                     running?
                                     #(max-reached? this)))))

  (stop [this]
    (log/info "Stopping poll loop")
    (reset! running? false)
    (-> this
        (assoc :result (deref (:future this) (* 2 (:poll-interval config)) :timeout))
        (dissoc :future))))
