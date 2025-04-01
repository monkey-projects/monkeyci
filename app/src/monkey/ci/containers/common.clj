(ns monkey.ci.containers.common)

(def home-dir "/home/monkeyci")
(def checkout-vol "checkout")
(def checkout-dir "/opt/monkeyci/checkout")
(def script-dir "/opt/monkeyci/script")
(def key-dir "/opt/monkeyci/keys")

(def script-vol "scripts")
(def job-script "job.sh")
(def config-vol "config")
(def config-dir "/home/monkeyci/config")
(def job-container-name "job")
(def config-file "config.edn")

(def sidecar-container-name "sidecar")

(def promtail-config-vol "promtail-config")
(def promtail-config-dir "/etc/promtail")
(def promtail-config-file "config.yml")
