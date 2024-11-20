(ns tests
  (:require [babashka.process :as bp]))

(defn gen-deps [watch?]
  {:aliases
   {:monkeyci/test
    {:extra-deps {'lambdaisland/kaocha {:mvn/version "1.91.1392"}}
     :paths ["."]
     :exec-fn 'kaocha.runner/exec-fn
     :exec-args (cond-> {:tests [{:type :kaocha.type/clojure.test
                                  :id :unit
                                  :ns-patterns ["-test$"]
                                  :source-paths ["."]
                                  :test-paths ["."]}]}
                  watch? (assoc :watch? true))}}})

(defn run-tests
  "Runs the unit tests for the script in given dir"
  [dir & [watch?]]
  (let [deps (pr-str (gen-deps (true? watch?)))
        {:keys [err out]} @(bp/process ["clojure" "-Sdeps" deps "-X:monkeyci/test"]
                                       {:err :string
                                        :out :string
                                        :dir dir})]

    (when out
      (println out))
    (when err
      (println err))))
