(ns monkey.ci.console
  "Functionality for printing to the console, probably using ANSI coloring."
  (:require [clansi :as cl]
            [clojure.string :as cs]
            [config.core :as cc]))

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
;;(def bad  (error "\u2613"))
(def prev-line "\033[F") ; ANSI code to jump back to the start of previous line

(defn url [url]
  (cl/style url :underline))

(defn overwrite
  "Overwrites the previous line with the string"
  [s & args]
  (apply println prev-line s args))

(defn rows
  "Returns number of lines in the console"
  ([env]
   (:lines env))
  ([]
   (rows cc/env)))

(defn cols
  "Returns number of columns in the console"
  ([env]
   (:columns env))
  ([]
   (cols cc/env)))

(def esc "Ansi escape code" "\033")
(def csi "Control sequence introducer" (str esc "["))
(def reset "Resets previous ansi instructions" (str csi "0m"))

(defn ctrl-code
  "Formats an Ansi control code with given parts"
  [& parts]
  (str csi (cs/join ";" parts)))

(defn font-code
  "Ansi control codes for font formatting"
  [& parts]
  (str (apply ctrl-code parts) "m"))

(defn color-256
  "Ansi control codes for 256 color code, with optional background color"
  [fg & [bg]]
  (apply font-code
         (cond-> [38 5 fg]
           bg (concat [48 5 bg]))))

(defn jump-up
  "Moves cursor up `n` lines"
  [n]
  (ctrl-code (str n "A")))

(defn erase-line
  "Erases part or all of the line. 
   For values of `n`:
     `0`: from cursor to EOL
     `1`: from cursor to beginning of line
     `2`: entire line (default)"
  ([n]
   (ctrl-code (str n "K")))
  ([]
   ;; Erase entire line
   (erase-line 2)))

(defn print-lines
  "Prints given sequence of lines"
  [lines]
  (doseq [l lines]
    (println l)))

(defn render-next
  "Used in a render loop: renders the next iteration, given the rendering
   state, which contains the previously rendered lines, the renderer and 
   rendering state.  The cursor jumps back to the original printing position,
   according to the number of lines previously printed."
  [{:keys [prev renderer state]}]
  (when (some? prev)
    (print (jump-up (count prev))))
  (let [[lines upd] (renderer state)]
    (print-lines lines)
    {:prev lines
     :state upd
     :renderer renderer}))
