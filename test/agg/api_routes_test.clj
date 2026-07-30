(ns agg.api-routes-test
  (:require [agg.api.routes :as routes]
            [clojure.test :refer [deftest is]]))

(deftest feature-route-seams-preserve-representative-public-contracts
  (is (= :auth
         (routes/feature-for {:method "GET" :path "/v1/auth/login/start"})))
  (is (= :preview
         (routes/feature-for {:method "GET"
                              :path "/v1/previews/00000000-0000-0000-0000-000000000000"})))
  (is (= :tokens
         (routes/feature-for {:method "POST" :path "/v1/tokens"})))
  (is (= :admin
         (routes/feature-for {:method "GET" :path "/v1/admin/members"})))
  (is (= :jobs
         (routes/feature-for {:method "POST" :path "/v1/jobs"})))
  (let [preparation
        {:method "POST" :path "/v1/derivative-preparations"}
        playback
        {:method "GET"
         :path
         "/v1/derivative-preparations/00000000-0000-0000-0000-000000000211/playback/00000000-0000-0000-0000-000000000212"}]
    (is (= :derivative-preparation (routes/feature-for preparation)))
    (is (= :derivative-preparation (routes/feature-for playback)))
    (is (routes/available-in-profile? preparation "api"))
    (is (false? (routes/available-in-profile? preparation "overlay")))
    (is (false? (routes/available-in-profile? preparation "proto"))))
  (is (= :not-found
         (routes/feature-for {:method "GET" :path "/not-a-route"}))))
