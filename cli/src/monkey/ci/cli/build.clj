(ns monkey.ci.cli.build
  "Functions for managing build objects"
  (:require [babashka.fs :as fs]
            [monkey.ci.utils.path :as up]))

(def script "Gets script from the build"
  :script)

(def default-script-dir ".monkeyci")

(def script-dir
  "Gets script dir from the build"
  (comp :script-dir script))

(defn set-script-dir [b d]
  (assoc-in b [:script :script-dir] d))

(def checkout-dir :checkout-dir)

(defn calc-script-dir
  "Given an (absolute) working directory and scripting directory, determines
   the absolute script dir."
  [wd sd]
  (->> (or sd default-script-dir)
       (up/abs-path wd)
       (fs/canonicalize)))
