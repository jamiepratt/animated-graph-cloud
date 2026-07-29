(ns agg.test-targeted-fixture
  (:require [clojure.test :refer [deftest is]]))

(deftest bounded-example
  (is (= 2 (+ 1 1))))
