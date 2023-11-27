(ns monkey.ci.reporting.print
  "Reporter that prints to the console using coloring."
  (:require [clansi :as cl]
            [clojure.string :as cs]
            [monkey.ci.reporting :as r]
            [monkey.ci.build.core :as bc]))

(def good (cl/style "\u221a" :bright :green))
(def bad  (cl/style "X" :bright :red))
(def prev-line "\033[F") ; ANSI code to jump back to the start of previous line

(defn- url [url]
  (cl/style url :underline))

(defn- accent [s]
  (cl/style s :bright :yellow))

(defn- overwrite
  "Overwrites the previous line with the string"
  [s & args]
  (apply println prev-line s args))

(defmulti printer :type)

(defn- print-stop []
  (println "Press" (cl/style "Ctrl+C" :cyan) "to stop."))

(defmethod printer :server/started [msg]
  (println "Server started at" (url (format "http://localhost:%d" (get-in msg [:http :port]))) good)
  (print-stop))

(defmethod printer :watch/started [m]
  (println "Watching for build events at" (url (:url m)))
  (print-stop))

(defn- step-name [event]
  (or (:name event)
      (str "index " (:index event))))

(defn- build-id [evt]
  (let [[_ _ _ bid] (:sid evt)]
    (cl/style (str "[" (or bid "unknown") "]") :cyan)))

(defmethod printer :build/event [{:keys [event]}]
  (letfn [(p [& args]
            (apply println (build-id event) args))]
    (case (:type event)
      :script/start
      (p (cl/style "Script started" :green))
      :script/end
      (p (cl/style "Script completed" :green))
      :pipeline/start
      (p "Pipeline started:" (accent (:pipeline event)))
      :pipeline/end
      (if (bc/success? event)
        (p "Pipeline" (accent (:pipeline event)) "succeeded" good)
        (p "Pipeline" (accent (:pipeline event)) "failed" bad))
      :step/start
      (p "Step started:" (accent (step-name event)))
      :step/end
      (if (bc/success? event)
        (p "Step succeeded:" (accent (step-name event)) good)
        (do
          (p "Step failed:" (accent (step-name event)) bad)
          (p "Message:" (accent (:message event)))
          (when-let [st (:stack-trace event)]
            (p "Stack trace:" (cl/style st :red)))))
      ;; Other cases, just ignore
      nil)))

(def col-space 4)
(def col-sep (apply str (repeat col-space \space)))

(defn- col-width
  "Calculates column with for the property `p` in items, which is
   the max length of the properties."
  [p items]
  (->> items
       (map (comp count str p))
       (reduce max 0)))

(defn- left-align [s w]
  (cond->> s
    (pos? w) (format (format "%%-%ds" w))))

(defn build-list
  "Generates list of strings to print to output for build list"
  [builds]
  (let [props [:id :timestamp :result]
        headers ["Id" "Timestamp" "Result"]
        widths (->> (map #(col-width % builds) props)
                    (map max (map count headers)))

        generate-title
        (fn []
          (cl/style 
           (->> (map left-align
                     headers
                     widths)
                (cs/join col-sep))
           :cyan))

        list-item
        (fn [{id :id ts :timestamp res :result :as b}]
          (->> (map left-align
                    [id ts (str (name res) " " (if (= :success res) good bad))]
                    widths)
               (cs/join col-sep)))]
    
    (if (empty? builds)
      [(cl/style "No builds found" :cyan)]
      (->> (map list-item builds)
           (into [(generate-title)])))))

(defn- print-all [l]
  (doseq [s l]
    (println s)))

(defmethod printer :build/list [{:keys [builds]}]
  (print-all (build-list builds)))

(defmethod printer :default [msg]
  (println (cl/style "Warning:" :bright :cyan) "unknown message type" (accent (str (:type msg)))))

(defn print-reporter
  "Nicely prints to stdout, using coloring"
  [obj]
  (printer obj))

(defmethod r/make-reporter :print [_]
  print-reporter)
