(ns leiningen.new.db
  (:require [leiningen.new.common :refer :all]))

(defn select-db [{:keys [features]}]
  (cond
    (some #{"+postgres"} features) :postgres
    (some #{"+mysql"} features) :mysql
    (some #{"+mongodb"} features) :mongo
    (some #{"+datomic"} features) :datomic
    (some #{"+crux"} features) :crux
    (some #{"+h2"} features) :h2
    (some #{"+sqlite"} features) :sqlite))

(defn db-dependencies [options]
  (into [['luminus-migrations "0.7.1"]
         ['conman "0.9.1"]]
        ({:postgres [['org.postgresql/postgresql "42.2.18"]]
          :mysql    [['mysql/mysql-connector-java "8.0.18"]
                     ['com.google.protobuf/protobuf-java "3.8.0"]]
          :h2       [['com.h2database/h2 "1.4.200"]]
          :sqlite   [['org.xerial/sqlite-jdbc "3.32.3"]]}
         (select-db options))))

(defn db-url [{:keys [sanitized] :as options} suffix]
  (let [user (or (System/getenv "USER") "db_user_name_here")]
    ({:postgres (str "postgresql://localhost/" sanitized "_" suffix
                     "?user=" user "&password=db_user_password_here")
      :mysql    (str "mysql://localhost:3306/" sanitized "_" suffix
                     "?user=" user "&password=db_user_password_here")
      :h2       (str "jdbc:h2:./" sanitized "_" suffix ".db")
      :sqlite   (str "jdbc:sqlite:" sanitized "_" suffix ".db")
      :mongo    (str "mongodb://127.0.0.1/" sanitized "_" suffix)
      :datomic  (str "datomic:free://localhost:4334/" sanitized "_" suffix)}
     (select-db options))))

(defn relational-db-files [options]
  (let [timestamp (.format
                    (java.text.SimpleDateFormat. "yyyyMMddHHmmss")
                    (java.util.Date.))]
    (concat
      [["{{db-path}}/{{sanitized}}/db/core.clj" "db/src/sql.db.clj"]
       ["{{resource-path}}/sql/queries.sql" "db/sql/queries.sql"]
       ["{{backend-test-path}}/{{sanitized}}/db/core_test.clj" "db/test/db/relational_db_test.clj"]]
      (when (some #{"+expanded"} (:features options))
        [[(str "{{resource-path}}/migrations/" timestamp "-add-users-table.up.sql") "db/migrations/add-users-table.up.sql"]
         [(str "{{resource-path}}/migrations/" timestamp "-add-users-table.down.sql") "db/migrations/add-users-table.down.sql"]]))))

(defn db-profiles [options]
  (merge
   options
   (if (:embedded-db options)
     {:database-profile-dev  (str :database-url " \"" (db-url options "dev") "\"")
      :database-profile-test (str :database-url " \"" (db-url options "test") "\"")}
     {:database-profile-dev  (str "; set your dev database connection URL here\n ; " :database-url " \"" (db-url options "dev") "\"\n")
      :database-profile-test (str "; set your test database connection URL here\n ; " :database-url " \"" (db-url options "test") "\"\n")})))

(def mongo-files
  [["{{db-path}}/{{sanitized}}/db/core.clj" "db/src/mongodb.clj"]])

(def datomic-files
  [["{{db-path}}/{{sanitized}}/db/core.clj" "db/src/datomic.clj"]
   ["{{resource-path}}/migrations/schema.edn" "db/migrations/schema.edn"]])

(def crux-files
  [["{{db-path}}/{{sanitized}}/db/core.clj" "db/src/crux.clj"]
   ["{{backend-test-path}}/{{sanitized}}/db/core_test.clj" "db/test/db/crux_test.clj"]])

(defn add-mongo [[assets options]]
  [(into assets mongo-files)
   (-> options
       (assoc
        :mongodb true
        :db-connection true
        :db-docs ((:selmer-renderer options) (slurp-resource "db/docs/mongo_instructions.md") options))
       (merge (db-profiles options))
       (append-options :dependencies [['com.novemberain/monger "3.1.0" :exclusions ['com.google.guava/guava]]
                                      ['com.google.guava/guava "27.0.1-jre"]]))])

(defn add-datomic [[assets {:keys [sanitized] :as options}]]
  [(into assets datomic-files)
   (let [info (str "\n ; alternatively, you can use the datomic mem db for development:"
                   "\n ; :database-url \"datomic:mem://" sanitized "_datomic_dev\"\n")]
     (-> options
         (assoc
          :datomic true
          :db-connection true
          :db-docs ((:selmer-renderer options) (slurp-resource "db/docs/datomic_instructions.md") options))
         (merge (db-profiles options))
         (update :database-profile-dev str info)
         (append-options :dependencies [['com.datomic/datomic-free "0.9.5697"
                                         :exclusions ['org.slf4j/log4j-over-slf4j
                                                      'org.slf4j/slf4j-nop
                                                      'com.google.guava/guava]]
                                        ['com.google.guava/guava "25.1-jre"]
                                        ['io.rkn/conformity "0.5.1"]])))])

(defn add-crux [[assets options]]
  [(into assets crux-files)
   (-> options
       (assoc
         :crux true
         :db-connection true
         :db-docs ((:selmer-renderer options) (slurp-resource "db/docs/crux_instructions.md") options)
         :database-profile-dev (str :crux-config "\n"
                                    (with-out-str (clojure.pprint/pprint
                                                    {:crux/index-store {:kv-store {:crux/module 'crux.rocksdb/->kv-store
                                                                                   :db-dir "data/indices"}}
                                                     :crux/document-store {:kv-store {:crux/module 'crux.rocksdb/->kv-store
                                                                                      :db-dir "data/docs"}}
                                                     :crux/tx-log {:kv-store {:crux/module 'crux.rocksdb/->kv-store
                                                                              :db-dir "data/transactions"}}})))
         :database-profile-test (str :crux-config " " {}))
       (append-options :dependencies [['juxt/crux-core "21.04-1.16.0-beta"]
                                      ['juxt/crux-rocksdb "21.04-1.16.0-beta"]]))])

(defn add-relational-db [db [assets options]]
  [(into assets (relational-db-files options))
   (let [embedded-db? (some #{(name db)} ["h2" "sqlite"])
         boot? (some #{"+boot"} (:features options))]
     (-> options
         (append-options :dependencies (db-dependencies options))
         (assoc
          :relational-db true
          :db-connection (not embedded-db?)
          :db-type (name db)
          :embedded-db embedded-db?
          :db-docs ((:selmer-renderer options)
                    (slurp-resource (if (= :h2 db)
                                      "db/docs/h2_instructions.md"
                                      "db/docs/db_instructions.md"))
                    options))
         (db-profiles)))])

(defn db-features [state]
  (if-let [db (select-db (second state))]
    (cond
      (= :mongo db) (add-mongo state)
      (= :datomic db) (add-datomic state)
      (= :crux db) (add-crux state)
      :else (add-relational-db db state))
    state))
