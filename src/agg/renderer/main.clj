(ns agg.renderer.main
  (:gen-class))

(defn -main [& _]
  (println (str "{\"severity\":\"INFO\","
                "\"component\":\"renderer\","
                "\"event\":\"smoke_complete\","
                "\"message\":\"Renderer smoke job completed\"}")))
