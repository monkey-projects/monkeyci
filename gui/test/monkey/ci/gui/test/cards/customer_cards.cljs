(ns monkey.ci.gui.test.cards.customer-cards
  (:require [devcards.core :refer-macros [defcard-rg]]
            [monkey.ci.gui.customer.views :as sut]
            [monkey.ci.gui.charts :as charts]
            [reagent.core]
            [re-frame.core :as rf]
            [re-frame.db :as rdb]))

(defcard-rg build-stats
  "Customer build statistics"
  (let [config {:elapsed-seconds
                [{:date 1729382400000, :seconds 0}
                 {:date 1729468800000, :seconds 7151}
                 {:date 1729555200000, :seconds 7283}
                 {:date 1729641600000, :seconds 5919}
                 {:date 1729728000000, :seconds 3257}
                 {:date 1729814400000, :seconds 5205}
                 {:date 1729900800000, :seconds 0}
                 {:date 1729987200000, :seconds 0}
                 {:date 1730073600000, :seconds 0}
                 {:date 1730160000000, :seconds 0}
                 {:date 1730246400000, :seconds 0}
                 {:date 1730332800000, :seconds 506}
                 {:date 1730419200000, :seconds 0}
                 {:date 1730505600000, :seconds 0}
                 {:date 1730592000000, :seconds 0}
                 {:date 1730678400000, :seconds 506}
                 {:date 1730764800000, :seconds 1171}
                 {:date 1730851200000, :seconds 1756}
                 {:date 1730937600000, :seconds 0}
                 {:date 1731024000000, :seconds 2392}
                 {:date 1731110400000, :seconds 0}
                 {:date 1731196800000, :seconds 0}
                 {:date 1731283200000, :seconds 0}
                 {:date 1731369600000, :seconds 3802}
                 {:date 1731456000000, :seconds 830}
                 {:date 1731542400000, :seconds 2848}
                 {:date 1731628800000, :seconds 3252}
                 {:date 1731715200000, :seconds 0}
                 {:date 1731801600000, :seconds 0}
                 {:date 1731888000000, :seconds 3187}
                 {:date 1731974400000, :seconds 4418}
                 {:date 1732060800000, :seconds 2901}],
                :consumed-credits
                [{:date 1729382400000, :credits 0}
                 {:date 1729468800000, :credits 150.0}
                 {:date 1729555200000, :credits 245.0}
                 {:date 1729641600000, :credits 194.0}
                 {:date 1729728000000, :credits 105.0}
                 {:date 1729814400000, :credits 155.0}
                 {:date 1729900800000, :credits 0}
                 {:date 1729987200000, :credits 0}
                 {:date 1730073600000, :credits 0}
                 {:date 1730160000000, :credits 0}
                 {:date 1730246400000, :credits 0}
                 {:date 1730332800000, :credits 13.0}
                 {:date 1730419200000, :credits 0}
                 {:date 1730505600000, :credits 0}
                 {:date 1730592000000, :credits 0}
                 {:date 1730678400000, :credits 18.0}
                 {:date 1730764800000, :credits 42.0}
                 {:date 1730851200000, :credits 81.0}
                 {:date 1730937600000, :credits 0}
                 {:date 1731024000000, :credits 74.0}
                 {:date 1731110400000, :credits 0}
                 {:date 1731196800000, :credits 0}
                 {:date 1731283200000, :credits 0}
                 {:date 1731369600000, :credits 118.0}
                 {:date 1731456000000, :credits 36.0}
                 {:date 1731542400000, :credits 97.0}
                 {:date 1731628800000, :credits 84.0}
                 {:date 1731715200000, :credits 0}
                 {:date 1731801600000, :credits 0}
                 {:date 1731888000000, :credits 123.0}
                 {:date 1731974400000, :credits 184.0}
                 {:date 1732060800000, :credits 30.0}]}]
    (rf/dispatch [:chart/update ::build-stats (sut/build-chart-config config)])
    [charts/chart-component ::build-stats]))
