(ns monkey.ci.gui.test.helpers
  (:require [martian.core :as martian]
            [martian.test :as mt]
            [monkey.ci.gui.martian :as mm]
            [re-frame.core :as rf]))

(defn catch-fx [fx]
  (let [inv (atom [])]
    (rf/reg-fx fx (fn [_ evt]
                    (swap! inv conj evt)))
    inv))

(defn initialize-martian
  "Initializes the martian client with given responses.  
   Use this in a `re-frame.test/run-test-async`"
  [responses]
  (let [m (-> (martian/bootstrap mm/url mm/routes)
              (mt/respond-as "cljs-http")
              (mt/respond-with responses))]
    (rf/dispatch-sync [:martian.re-frame/init m])))

