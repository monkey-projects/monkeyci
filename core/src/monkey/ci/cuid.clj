(ns monkey.ci.cuid
  "Functions for working with cuids, which are like uuids but are a bit better
   to handle for humans.")

(def cuid-chars (->> [[\A \Z] [\a \z] [\0 \9]]
                     (mapcat (comp (fn [[s e]] (range s (inc e))) (partial map int)))
                     (mapv char)
                     (vec)))
(def cuid-length 24)

(defn random-cuid
  "Generates a random 24 char cuid, which is like a UUID but (a little bit) more human-readable.
   Also, cuids have more possible values than uuids, at the cost of consuming 50% more memory."
  []
  (->> (repeatedly cuid-length #(get cuid-chars (rand-int (count cuid-chars))))
       (apply str)))

(def cuid-regex #"[A-Za-z0-9]{24}")

(defn cuid? [x]
  (and x (some? (re-matches cuid-regex x))))
