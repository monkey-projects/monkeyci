(ns monkey.ci.entities.migrations-test
  (:require [clojure.test :refer [deftest testing is]]
            [honey.sql :as sql]
            [monkey.ci.entities.migrations :as sut]))

(deftest fk
  (testing "creates foreign key constraint with cascading"
    (is (= "CREATE TABLE test (FOREIGN KEY(src_col) REFERENCES dest_table(dest_col) ON DELETE CASCADE)"
           (-> {:create-table :test
                :with-columns [(sut/fk :src-col :dest-table :dest-col)]}
               sql/format
               first))))

  (testing "no cascading delete when specified"
    (is (= "CREATE TABLE test (FOREIGN KEY(src_col) REFERENCES dest_table(dest_col))"
           (-> {:create-table :test
                :with-columns [(sut/fk :src-col :dest-table :dest-col true)]}
               sql/format
               first)))))
