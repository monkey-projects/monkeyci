(ns monkey.ci.gui.forms
  (:require [re-frame.core :as rf]))

(defn get-form-data
  "Retrieves the form values from the given form element.  Returns a map
   with the form names as keys (as a keyword) and the form values as 
   values (as a vector).  Values are vectors because they can sometimes
   have multiple values (e.g. for selects or checkboxes)."
  [el]
  (let [fd (js/FormData. el)]
    (reduce (fn [r k]
              (assoc r (keyword k) (js->clj (.getAll fd k))))
            {}
            (.keys fd))))

(defn submit-handler
  "Form submission handler that dispatches the given event instead of 
   the default behaviour.  The form values are added to the event."
  [evt & [form-id]]
  (fn [e]
    (.preventDefault e)
    (rf/dispatch (conj evt (get-form-data (if form-id
                                            (.getElementById js/document (name form-id))
                                            (.. e -target)))))))

(defn form-input
  "Renders a generic form input group, with label and optional help text."
  [{:keys [id label value help-msg extra-opts]}]
  (let [help-id (str id "-help")]
    [:<>
     [:label.form-label {:for id} label]
     [:input.form-control (merge
                           {:id id
                            :name id
                            :aria-describedby help-id
                            (if (contains? extra-opts :on-change)
                              :value
                              :default-value) value}
                           extra-opts)]
     (when help-msg
       [:span.form-text
        {:id help-id}
        help-msg])]))
