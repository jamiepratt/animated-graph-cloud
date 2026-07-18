(ns agg.errors-test
  (:require [agg.errors :as errors]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest raise-captures-source-and-only-safe-context
  (let [error (try
                (errors/raise! "Invalid request"
                               {:type ::invalid-request
                                :status 400
                                :request-body "telemetry,heart_rate"
                                :telemetry "120"
                                :token "secret"
                                :credential "secret"
                                :filename "private.mov"
                                :signed-url "https://storage.example/signed"
                                :failed-value {:password "secret"}})
                nil
                (catch clojure.lang.ExceptionInfo error
                  error))]
    (is (= "Invalid request" (.getMessage error)))
    (is (= ::invalid-request (:type (ex-data error))))
    (is (= 400 (:status (ex-data error))))
    (is (= #{:type :status :source}
           (set (keys (ex-data error)))))
    (is (= "errors_test.clj"
           (last (str/split (get-in (ex-data error) [:source :file]) #"/"))))
    (is (integer? (get-in (ex-data error) [:source :line])))
    (is (integer? (get-in (ex-data error) [:source :column])))))

(deftest raise-requires-a-namespaced-type
  (testing "plain keywords are rejected before an application error is raised"
    (is (thrown-with-msg? IllegalArgumentException
                          #"namespaced"
                          (errors/raise! "Invalid" {:type :invalid}))))
  (testing "missing types are rejected"
    (is (thrown? IllegalArgumentException
                 (errors/raise! "Invalid" {})))))

(deftest wrapping-adds-source-and-preserves-cause
  (let [inner (try
                (errors/raise! "Inner" {:type ::inner :line 12})
                (catch clojure.lang.ExceptionInfo error
                  error))
        outer (try
                (errors/raise! "Outer" {:type ::outer} inner)
                (catch clojure.lang.ExceptionInfo error
                  error))]
    (is (identical? inner (.getCause outer)))
    (is (= ::outer (:type (ex-data outer))))
    (is (= ::inner (:type (ex-data inner))))
    (is (map? (:source (ex-data outer))))
    (is (map? (:source (ex-data inner))))
    (is (not= (get-in (ex-data outer) [:source :line])
              (get-in (ex-data inner) [:source :line])))))

(deftest raise-does-not-log
  (is (= ""
         (with-out-str
           (try
             (errors/raise! "Expected validation" {:type ::validation})
             (catch clojure.lang.ExceptionInfo _))))))
