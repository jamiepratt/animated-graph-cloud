(ns agg.smoke-test
  (:require [agg.api.main :as api]
            [agg.renderer.main :as renderer]
            [clojure.test :refer [deftest is]]))

(deftest entry-points-load
  (is (= "Animated Graph Cloud API bootstrap\n"
         (with-out-str (api/-main))))
  (is (= "Animated Graph Cloud renderer bootstrap\n"
         (with-out-str (renderer/-main)))))

