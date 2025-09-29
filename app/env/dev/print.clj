(ns print
  (:require [monkey.ci.console :as c]
            [monkey.ci.local.print :as p]))

(defn build-start []
  (p/build-start
   p/console-printer
   {:event
    {:sid ["test-org" "test-repo" "test-build"]}}))

(defn build-init []
  (p/build-init
   p/console-printer
   {:event
    {:build
     {:build-id "test-build"
      :checkout-dir "/tmp/test-checkout"
      :script
      {:script-dir ".monkeyci/"}}}}))

(defn build-end-success []
  (p/build-end
   p/console-printer
   {:event
    {:status :success}}))

(defn build-end-failure []
  (p/build-end
   p/console-printer
   {:event
    {:status :failure
     :message "Build error"}}))

(defn script-start []
  (p/script-start
   p/console-printer
   {:event
    {:jobs
     [{:id "job-1"}
      {:id "job-2"}]}}))

(defn script-end-with-msg []
  (p/script-end
   p/console-printer
   {:event
    {:message "Test msg"}}))

(defn script-end-without-msg []
  (p/script-end
   p/console-printer
   {}))

(defn job-start []
  (p/job-start
   p/console-printer
   {:event
    {:job-id "test-job"}}))

(defn job-end-success []
  (p/job-end
   p/console-printer
   {:event
    {:job-id "test-job"
     :status :success}}))

(defn job-end-failure []
  (p/job-end
   p/console-printer
   {:event
    {:job-id "test-job"
     :status :failure
     :result
     {:output "Some error occurred"}}}))

(defn colors-example []
  (c/print-lines
   [(str (c/color-256 15) "Bright white" c/reset)
    (str (c/font-code 1) (c/color-256 15) "Bright white bold" c/reset)
    (str (c/font-code 4) (c/color-256 119) "Bright green underlined" c/reset)
    (str (c/color-256 0 87) "Cyan background" c/reset)
    (str (c/font-code 3) (c/color-256 202) "Orange italic" c/reset)]))

(defn decimal-progress
  "Returns char to use for value that is less than 1.  `v` should be between
   0 and 1."
  [v]
  (char (- 0x258f (int (* v 8)))))

(defn progress-bar
  "Draws progress bar of given width (chars) with percentage filled"
  [w p]
  (let [f "\u2588"
        u #_"\u2591" " "
        n (int (* w p))
        d (- (* w p) n)
        r (cond-> (- w n)
            (pos? d) dec)]
    (apply str (->> (concat (repeat n f)
                            (when (pos? d)
                              [(decimal-progress d)])
                            (repeat r u))
                    (remove nil?)))))

(def white (c/color-256 15))

(defn fancy-progress-bar [{:keys [width value fg bg]
                           :or {fg 75
                                bg 19}}]
  (str white "[ "
       (c/color-256 fg bg) (progress-bar width value)
       c/reset
       white " ]"
       c/reset))

(defn- format-job [{:keys [po pw]} {:keys [id status] :as job}]
  (let [colors {:success {:fg 76 :bg 22}
                :failure {:fg 160 :bg 88}
                :running {:fg 75 :bg nil}
                :pending {:fg 252 :bg nil}}
        msgs {:success (str (c/color-256 10) "\u221a Success!")
              :failure (str (c/color-256 196) "x Failure")
              :running (str white "  00m45s")}
        values {:success 1
                :failure 1
                :running 0.5
                :pending 0}]
    (str "  " white id (apply str (repeat (- po (count id) 2) " "))
         (fancy-progress-bar (merge {:width pw :value (get values status)}
                                    (get colors status)))
         " " (get msgs status)
         c/reset)))

(defn build-example
  "Example of how build progress could look like"
  []
  (let [jobs [{:id "test"
               :status :success}
              {:id "integration"
               :status :failure}
              {:id "publish"
               :status :running}
              {:id "notify"
               :status :pending}]
        opts {:pw 40
              :po (->> jobs
                       (map (comp count :id))
                       (apply max)
                       (+ 4))}]
    (c/print-lines
     (concat
      [(str "Running build: " (c/font-code 1) (c/color-256 81) "test-build" c/reset)
       (str "This build has " white (count jobs) " jobs:" c/reset)]
      (map (partial format-job opts) jobs)
      [(str "Elapsed time: " white "02m15s")]))))
