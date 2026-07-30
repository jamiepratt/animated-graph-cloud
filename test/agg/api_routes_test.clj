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
  (is (= :jobs
         (routes/feature-for
          {:method "POST"
           :path
           "/v1/derivative-preparations/00000000-0000-0000-0000-000000000195/playback-sessions"})))
  (is (= :jobs
         (routes/feature-for
          {:method "GET"
           :path
           "/v1/derivative-preparations/00000000-0000-0000-0000-000000000195/playback/00000000-0000-0000-0000-000000000196"})))
  (is (= :not-found
         (routes/feature-for {:method "GET" :path "/not-a-route"}))))
