(ns agg.browser-fixture
  (:require [agg.api.main :as api]
            [agg.auth.core :as auth]
            [agg.jobs.lifecycle :as jobs]
            [agg.tokens.core :as tokens])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (java.net InetSocketAddress URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)))

(def api-port 18765)
(def oauth-port 18766)

(defn- query-params [uri]
  (->> (or (.getRawQuery uri) "")
       (#(.split ^String % "&"))
       (keep (fn [part]
               (when-not (.isBlank ^String part)
                 (let [[key value] (.split ^String part "=" 2)]
                   [(URLDecoder/decode key StandardCharsets/UTF_8)
                    (URLDecoder/decode (or value "")
                                       StandardCharsets/UTF_8)]))))
       (into {})))

(defn- oauth-server []
  (let [server (HttpServer/create (InetSocketAddress. oauth-port) 0)]
    (.createContext
     server "/authorize"
     (reify HttpHandler
       (handle [_ exchange]
         (let [params (query-params (.getRequestURI exchange))
               location (str (get params "redirect_uri")
                             "?code=browser-code&state="
                             (URLEncoder/encode (get params "state")
                                                StandardCharsets/UTF_8))]
           (.set (.getResponseHeaders exchange) "Location" location)
           (.sendResponseHeaders exchange 302 -1)
           (.close (.getResponseBody exchange))))))
    (.start server)
    server))

(defn -main [& _]
  (let [oauth (reify auth/OAuthClient
                (exchange-code! [_ flow _ _ _]
                  (case flow
                    :login {:subject "browser-owner"
                            :email "owner@example.com"
                            :email-verified? true}
                    (throw (ex-info "Drive is not part of this browser fixture"
                                    {})))))
        auth-system (auth/system
                     {:client-id "browser-client"
                      :client-secret "browser-secret"
                      :base-url (str "http://localhost:" api-port)
                      :allowlist #{"owner@example.com" "member@example.com"}
                      :session-key (.getBytes "01234567890123456789012345678901")
                      :oauth oauth
                      :authorization-endpoint
                      (str "http://localhost:" oauth-port "/authorize")})
        lifecycle (jobs/in-memory-system)
        token-system (tokens/in-memory-system
                      {:pepper (.getBytes "abcdefghijklmnopqrstuvwxyz012345")})
        oauth-server (oauth-server)
        api-server (api/start! api-port
                               {:auth-system auth-system
                                :job-service (:service lifecycle)
                                :token-service (:service token-system)})]
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. (fn []
                (.close ^java.lang.AutoCloseable api-server)
                (.stop oauth-server 0))))
    (println (str "browser-fixture-ready http://localhost:" api-port))
    (flush)
    @(promise)))
