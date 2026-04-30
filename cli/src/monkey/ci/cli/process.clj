(ns monkey.ci.cli.process)

(defn run
  "Runs the given command vector in the specified working directory.
   Inherits stdio so output streams directly to the terminal.
   Returns the exit code of the child process.

   An optional `env` map of String->String is merged into the child
   process environment (on top of the current process environment)."
  ([cmd dir]
   (run cmd dir {}))
  ([cmd dir env]
   (let [pb (-> (ProcessBuilder. ^java.util.List (map str cmd))
                (.directory (java.io.File. (str dir)))
                (.inheritIO))]
     (when (seq env)
       (let [proc-env (.environment pb)]
         (doseq [[k v] env]
           (.put proc-env (str k) (str v)))))
     (-> pb (.start) (.waitFor)))))
