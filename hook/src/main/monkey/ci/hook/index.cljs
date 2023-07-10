(ns monkey.ci.hook.index
  "ClojureScript implementation of a GCP function that handles incoming webhooks."
  (:require ["@google-cloud/functions-framework" :as ff]
            ["@google-cloud/run" :as run]
            [clojure.string :as cs]))

(defn- log [& msg]
  (->> {:message (cs/join " " msg)
        :timestamp (js/Date.)
        :level "DEBUG"}
       (clj->js)
       (.stringify js/JSON)
       (println)))

(defn- create-job [config]
  (let [v2 (.-v2 run)
        sc (.-JobsClient v2)
        client (sc.)]
    ;; TODO Invoke the right client method here
    ;; TODO Make request arguments configurable
    ;; Returns a promise
    (.listJobs client (clj->js {:parent "projects/209111224299/locations/europe-west1"}))))

(defn- handle-trigger [req res]
  (let [body (js->clj (.-body req) :keywordize-keys true)
        reply (fn [v]
                (.send res (clj->js v)))]
    (log (str "Handling trigger: " body))
    ;; TODO Create a cloud run job that will execute the build script
    (-> (create-job body)
        (.then #(reply {:status "OK"
                        :repository (:repository body)
                        :jobs %}))
        (.catch #(reply {:error (str "Failed to create job: " %)})))))

(defn main [& args]
  ;; Register the handler.  The name must match the configuration in Terraform.
  (.http ff "buildTrigger" handle-trigger))
