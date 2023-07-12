(ns monkey.ci.test.runner.junit
  (:require [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.test :as t]
            [clojure.test.junit :as tj]
            [clojure.tools.namespace.find :as nf]
            [monkey.ci.test.build.core-test]))

(defn- find-namespaces
  "Finds all test namespaces"
  [dir]
  (let [re #"^monkey\.ci\.test.*-test$"]
    (->> (nf/find-namespaces-in-dir (io/file dir))
         (map name)
         (filter (partial re-matches re))
         (map symbol))))

(def require-all (partial apply require))

(def run-tests (partial apply t/run-tests))

(defn run-all [[dir]]
  (tj/with-junit-output
    (doto (find-namespaces (or dir "test"))
      (require-all)
      (run-tests))))
