(ns agg.api.admin-routes
  (:require [agg.admin.core :as admin]))

(defn list! [{:keys [respond-json]} exchange admin-service user]
  (respond-json exchange 200 (admin/list-members admin-service user)))

(defn add! [{:keys [request-json respond-json]} exchange admin-service user]
  (respond-json exchange 201
                (admin/add-member! admin-service user
                                   (:email (request-json exchange)))))

(defn revoke! [{:keys [request-json respond-json]} exchange admin-service user]
  (respond-json exchange 200
                (admin/revoke-member! admin-service user
                                      (:email (request-json exchange)))))
