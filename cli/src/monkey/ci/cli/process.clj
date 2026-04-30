(ns monkey.ci.cli.process)

(defn run
  "Runs the given command vector in the specified working directory.
   Inherits stdio so output streams directly to the terminal.
   Returns the exit code of the child process."
  [cmd dir]
  (-> (ProcessBuilder. ^java.util.List (map str cmd))
      (.directory (java.io.File. (str dir)))
      (.inheritIO)
      (.start)
      (.waitFor)))
