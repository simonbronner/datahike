(ns benchmark
  (:require [datahike.api :as d]))

(def sample-size 50000)

(defn create-data [conn sample-size]
  (let [cars ["Audi" "Mercedes" "Porsche" "Opel" "VW"]]
    (println "Creating test data of size" sample-size)
    (time(loop [n     0
                users (mapcat
                       (fn [i]
                         (let [car-id (d/tempid -1)]
                           [{:db/id    car-id
                             :car/type (rand-nth cars)}
                            {:db/id     (d/tempid -1)
                             :user/name (str "n" i)
                             :user/age  (rand-int 100)
                             :user/car  car-id}]))
                       (range sample-size))]
           (if (empty? users)
             {:datoms n}
             (let [[txs next-txs] (split-at 100 users)]
               (recur (+ n (count @(d/transact conn (vec txs))))
                      next-txs)))))
    conn))

(defn init-db [sample-size]
  (let [uri "datahike:file:///tmp/dh-bench"
        schema {:user/car {:db/cardinality :db.cardinality/many
                           :db/valueType   :db.type/ref}}]
    (d/delete-database uri)
    (d/create-database-with-schema uri schema)
    (create-data (d/connect uri) sample-size)))

(def conn (init-db sample-size))

(defn b-q [conn query]
  (time (d/q query @conn)))

(d/q '[:find (count ?e) . :where [?e ?a ?v]] @conn)

(let [direct-q  '[:find ?a ?v
                  :where
                  [100 ?a ?v]]
      ref-q     '[:find ?c
                  :where
                  [?e :user/name "n50"]
                  [?e :user/car ?c]]
      union-ref '[:find ?c ?a ?v
                  :where
                  [?e :user/name "n50"]
                  [?e :user/car ?c]
                  [?c ?a ?v]]
      union-ref-2 '[:find ?c ?a ?v
                    :where
                    [?c ?a ?v]
                    [?e :user/name "n50"]
                    [?e :user/car ?c]]
      non-union-ref '[:find ?c ?a ?v
                      :where
                      [?e :user/car ?c]
                      [?c ?a ?v]]]

  #_(println "\ndirect")
  #_(b-q conn direct-q)
  #_(println "\nref")
  #_(b-q conn ref-q)
  (println "------------------------")
  (b-q conn union-ref)
  (b-q conn union-ref-2)
  (b-q conn non-union-ref)
  true)


