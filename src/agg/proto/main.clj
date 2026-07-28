(ns agg.proto.main
  (:require [agg.api.main :as api]
            [agg.auth.gcp :as gcp]
            [agg.observability :as observability])
  (:gen-class))

(defn -main [& _]
  (let [port (parse-long (get (System/getenv) "PORT" "8080"))
        dependencies (if (= "true" (get (System/getenv)
                                        "AGG_JOB_LIFECYCLE_ENABLED"))
                       (gcp/api-system)
                       {})]
    (api/start! port (assoc dependencies :service-profile "proto"))
    (observability/emit-event! "api" "server_started"
                               {:message "Proto API server started"
                                :port port})))
