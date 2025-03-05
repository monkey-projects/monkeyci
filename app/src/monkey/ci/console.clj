(ns monkey.ci.console
  "Functionality for printing to the console, probably using ANSI coloring."
  (:require [clansi :as cl]))

(defn error [s]
  (cl/style s :bright :red))

(defn warning [s]
  (cl/style s :bright :yellow))

(defn success [s]
  (cl/style s :bright :green))

(defn accent [s]
  (cl/style s :bright :yellow))

(def good (success "\u221a"))
(def bad  (error "X"))
(def prev-line "\033[F") ; ANSI code to jump back to the start of previous line

(defn url [url]
  (cl/style url :underline))

(defn overwrite
  "Overwrites the previous line with the string"
  [s & args]
  (apply println prev-line s args))
