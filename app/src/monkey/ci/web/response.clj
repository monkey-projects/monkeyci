(ns monkey.ci.web.response)

(def get-events ::events)

(defn add-events [r evts]
  (update r ::events concat evts))

(defn add-event [r evt]
  (update r ::events conj evt))

(defn remove-events [r]
  (dissoc r ::events))
