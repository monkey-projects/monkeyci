(ns monkey.ci.events.mailman.bridge
  "Bridge event handlers.  Provided as a temporary compatibility layer between
   old-style event system and the newer mailman style.  In time, this will be
   removed as we fully migrate to mailman.")

;; TODO

(def old->new-routes
  [])

(def new->old-routes
  [[:build/updates []]])
