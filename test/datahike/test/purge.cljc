(ns datahike.test.purge
  (:require
    #?(:cljs [cljs.test :as t :refer-macros [is are deftest testing]]
       :clj  [clojure.test :as t :refer [is are deftest testing use-fixtures]])
    [datahike.test.core :as tdc]
    [datahike.api :as d]))

(def schema-tx [{:db/ident       :name
                 :db/valueType   :db.type/string
                 :db/unique      :db.unique/identity
                 :db/index       true
                 :db/cardinality :db.cardinality/one}
                {:db/ident       :age
                 :db/valueType   :db.type/long
                 :db/cardinality :db.cardinality/one}
                {:name "Alice"
                 :age  25}
                {:name "Bob"
                 :age  35}])

(defn create-test-db [uri]
  (d/delete-database uri)
  (d/create-database uri :initial-tx schema-tx)
  (d/connect uri))

(defn find-age [db name]
  (d/q '[:find ?a . :in $ ?n :where [?e :name ?n] [?e :age ?a]] db name))

(defn find-entity [db name]
  (d/q '[:find (pull ?e [:name :age]) :in $ ?n :where [?e :name ?n]] db name))

(defn find-entities [db]
  (into #{}
        (d/q '[:find [(pull ?e [:name :age]) ...] :where [?e :name _]] db)))

(deftest test-purge
  (let [conn (create-test-db "datahike:mem://test-purge")]
    (testing "retract datom, data is removed from current db and found in history"
      (let [name "Alice"]
        (d/transact conn [[:db/retract [:name "Alice"] :age 25]])
        (are [x y] (= x y)
                   true (nil? (find-age @conn name))
                   25 (find-age (d/history @conn) name))))
    (testing "purge datom from current index and from history"
      (let [name "Bob"]
        (d/transact conn [[:db/purge [:name "Bob"] :age 35]])
        (are [x y] (= x y)
                   true (nil? (find-age @conn name))
                   true (nil? (find-age (d/history @conn) name)))))
    (testing "purge retracted datom"
      (let [name "Alice"]
        (d/transact conn [[:db/purge [:name name] :age 25]])
        (are [x y] (= x y)
                   true (nil? (find-age @conn name))
                   true (nil? (find-age (d/history @conn) name)))))))

(deftest test-purge-attribute
  (let [conn (create-test-db "datahike:mem://test-purge-attribute")]
    (testing "purge attribute from current index"
      (let [name "Alice"]
        (d/transact conn [[:db.purge/attribute [:name "Alice"] :age]])
        (are [x y] (= x y)
                   true (nil? (find-age @conn name))
                   true (nil? (find-age (d/history @conn) name))
                   #{["Alice"] ["Bob"]} (d/q '[:find ?n :where [_ :name ?n]] @conn))))
    (testing "retract attribute from current index and purge from history"
      (let [name "Bob"]
        (testing "retracting from current index"
          (d/transact conn [[:db.fn/retractAttribute [:name name] :age]])
          (are [x y] (= x y)
                     true (nil? (find-age @conn name))
                     35 (find-age (d/history @conn) name)))
        (testing "purging from history"
          (d/transact conn [[:db.purge/entity [:name name] :age]])
          (are [x y] (= x y)
                     true (nil? (find-age @conn name))
                     true (nil? (find-age (d/history @conn) name))))))))

(deftest test-purge-entity
  (let [conn (create-test-db "datahike:mem://test-purge-entity")]
    (testing "purge entity from current index"
      (is (= #{{:name "Alice" :age 25} {:name "Bob" :age 35}} (find-entities @conn)))
      (d/transact conn [[:db.purge/entity [:name "Alice"]]])
      (is (= #{{:name "Bob" :age 35}} (find-entities @conn)))
      (is (= #{{:name "Bob" :age 35}} (find-entities (d/history @conn)))))
    (testing "retract entity from current index and purge from history"
      (let [name "Bob"]
        (testing "retracting from current index"
          (d/transact conn [[:db/retractEntity [:name name]]])
          (is (= #{} (find-entities @conn)))
          (is (= #{{:name "Bob" :age 35}} (find-entities (d/history @conn)))))
        (testing "purging from history"
          (d/transact conn [[:db.purge/entity [:name name]]])
          (is (= #{} (find-entities @conn)))
          (is (= #{} (find-entities (d/history @conn)))))))
    (testing "purge something that is not present in the database"
      (is (thrown-msg?
            "Can't find entity with ID [:name \"Alice\"] to be purged"
            (d/transact conn [[:db.purge/entity [:name "Alice"]]]))))))

(deftest test-purge-non-temporal-database
  (let [uri "datahike:mem://purge-non-temporal"
        _ (d/delete-database uri)
        _ (d/create-database uri :initial-tx schema-tx :temporal-index false)
        conn (d/connect uri)]
    (testing "purge data in non temporal database"
      (is (thrown-msg?
            "Purge entity is only available in temporal databases."
            (d/transact conn [[:db.purge/entity [:name "Alice"]]]))))))

(defn find-ages [db name]
  (into #{}
        (d/q '[:find [?a ...] :in $ ?n :where [?e :name ?n] [?e :age ?a]] db name)))

(deftest test-history-purge-before
  (let [conn (create-test-db "datahike:mem://purge-history-before")
        name "Alice"]
    (is (= #{25} (find-ages @conn name)))
    (testing "remove all historical data before date"
      (let [before-date (java.util.Date.)]
        (d/transact conn [{:db/id [:name name] :age 30}])
        (is (= #{30} (find-ages @conn name)))
        (is (= #{25 30} (find-ages (d/history @conn) name)))
        (d/transact conn [[:db.history.purge/before before-date]])
        (is (= #{30} (find-ages @conn name)))
        (is (= #{30} (find-ages (d/history @conn) name)))))))
