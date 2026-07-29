(ns agg.test-targeted-test
  (:require [agg.test-targeted :as targeted]
            [agg.test-targeted-fixture]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest targeted-runner-reports-each-test-before-running-it
  (let [result (atom nil)
        output (with-out-str
                 (binding [clojure.test/*test-out* *out*]
                   (reset! result
                           (targeted/run-tests
                            ['agg.test-targeted-fixture]))))]
    (is (str/includes?
         output
         "Running agg.test-targeted-fixture/bounded-example"))
    (is (= 0 (+ (:fail @result) (:error @result))))))
