(ns {{name}}.routes.home
  (:require [{{name}}.layout :as layout]
            [clojure.java.io :as io]
            [compojure.core :refer :all]
            [clojure.pprint :refer [pprint]]))

(defn home-page []
      (layout/render
        "app.html" {:docs (-> "md/docs.md" io/resource slurp)}))

(defn save-document [doc]
      (pprint doc)
      {:status "ok"})

(defroutes home-routes
  (GET "/" [] (home-page))
  (POST "/save" {:keys [body-params]}
        (save-document body-params)))