(ns monkey.ci.runners.oci2
  "Variation of the oci runner, that creates a container instance with two containers:
   one running the controller process, that starts the build api, and the other that
   actually runs the build script.  This is more secure and less error-prone than
   starting a child process.")

(defn run-controller [])

(defn run-script [])

