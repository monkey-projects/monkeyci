(ns monkey.ci.reporting.print
  "Reporter that prints to the console using coloring."
  (:require [babashka.fs :as fs]
            [clansi :as cl]
            [clojure.string :as cs]
            [monkey.ci
             [console :as c]
             [reporting :as r]]
            [monkey.ci.build :as b]
            [monkey.ci.build.core :as bc]))

(defmulti printer :type)

(defn- print-stop []
  (println "Press" (cl/style "Ctrl+C" :cyan) "to stop."))

(defmethod printer :server/started [msg]
  (println "Server started at" (c/url (format "http://localhost:%d" (get-in msg [:http :port]))) c/good)
  (print-stop))

(defmethod printer :watch/started [m]
  (println "Watching for build events at" (c/url (:url m)))
  (print-stop))

(defn- step-name [event]
  (or (:name event)
      (str "index " (:index event))))

(defn- build-id [evt]
  (let [[_ _ _ bid] (:sid evt)]
    (cl/style (str "[" (or bid "unknown") "]") :cyan)))

(defmethod printer :build/event [{:keys [event]}]
  (letfn [(p [& args]
            (apply println (build-id event) args))
          (pn [evt]
            (get-in evt [:pipeline :name]))]
    (case (:type event)
      :script/start
      (p (cl/style "Script started" :green))
      :script/end
      (p (cl/style "Script completed" :green))
      :pipeline/start
      (p "Pipeline started:" (c/accent (pn event)))
      :pipeline/end
      (if (bc/success? event)
        (p "Pipeline" (c/accent (pn event)) "succeeded" c/good)
        (p "Pipeline" (c/accent (pn event)) "failed" c/bad))
      :step/start
      (p "Step started:" (c/accent (step-name event)))
      :step/end
      (if (bc/success? event)
        (p "Step succeeded:" (c/accent (step-name event)) c/good)
        (do
          (p "Step failed:" (c/accent (step-name event)) c/bad)
          (p "Message:" (c/accent (:message event)))
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
                    [id ts (if res
                             (str (name res) " " (if (= :success res) c/good c/bad))
                             "unknown")]
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

(defmethod printer :verify/success [{:keys [jobs]}]
  (println (c/success "Success!") "Build script provides" (count jobs) "jobs with the current settings.")
  (doseq [j jobs]
    (println "  " (c/accent (:id j)))))

(defmethod printer :verify/failed [{:keys [message]}]
  (println (c/error "Error:") "Exception while verifying:" message))

(defn- print-finding [{:keys [row col message filename] :as f}]
  (printf "%s - at %d:%d: %s%n" (fs/file-name filename) row col message))

(defmethod printer :verify/result [{:keys [result]}]
  (let [{e :error w :warning} (:summary result)
        e? (and e (pos? e))
        w? (and w (pos? w))]
    (when e?
      (println (c/error (str "Got " e " error(s)"))))
    (when w?
      (println (c/warning (str "Got " w " warning(s)"))))
    (when (not (or e? w?))
      (println (c/success "Build script is valid!")))
    (doseq [f (:findings result)]
      (print-finding f))
    ;; Ensure printed stuff is actually sent to stdout
    (flush)))

(defmethod printer :test/starting [{:keys [build]}]
  (println (c/accent "Starting unit tests for build..."))
  (println "Location:" (b/script-dir build)))

(defmethod printer :default [msg]
  (println (c/warning "Warning:") "unknown message type" (c/accent (str (:type msg)))))

(defn print-reporter
  "Nicely prints to stdout, using coloring"
  [obj]
  (printer obj))

(defmethod r/make-reporter :print [_]
  print-reporter)
