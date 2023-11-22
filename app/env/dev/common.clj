(ns common)

(defonce env (atom :staging))

(defn set-env! [e]
  (reset! env e))

(defn staging! []
  (set-env! :staging))

(defn prod! []
  (set-env! :prod))
