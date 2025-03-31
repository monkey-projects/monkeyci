(ns monkey.ci.dispatcher.state
  "Event state management functions")

(def get-assignments :assignments)

(defn set-assignment [s id a]
  (assoc-in s [:assignments id] a))

(defn get-assignment [s id]
  (get-in s [:assignments id]))

(defn remove-assignment [s id]
  (update s :assignments dissoc id))

(defn get-queue [s]
  (:queued-list s))

(defn set-queue [s q]
  (assoc s :queued-list q))

(defn update-queue [s f & args]
  (apply update s :queued-list f args))

(def get-runners :runners)

(defn set-runners [s r]
  (assoc s :runners r))
