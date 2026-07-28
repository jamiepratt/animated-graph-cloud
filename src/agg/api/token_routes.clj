(ns agg.api.token-routes
  (:require [agg.tokens.core :as tokens]))

(defn create! [{:keys [request-json respond-json]} exchange token-service user]
  (respond-json exchange 201
                (tokens/create-token! token-service user
                                      (:name (request-json exchange)))))

(defn list! [{:keys [respond-json]} exchange token-service user]
  (respond-json exchange 200 (tokens/list-tokens token-service (:subject user))))

(defn revoke! [{:keys [respond-json]} exchange token-service user token-id]
  (respond-json exchange 200
                (tokens/revoke-token! token-service (:subject user) token-id)))
