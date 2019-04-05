(ns jobryant.datomic.api
  (:refer-clojure :exclude [sync filter])
  (:require [jobryant.util :as u]
            [jobryant.datomic.util :refer [ref? expand-schema]]
            [datomic.api :as d]
            [clojure.java.io :refer [reader writer]]
            [me.raynes.fs :as fs]
            [clojure.string :as str]))

(u/pullall datomic.api)

(declare ^:private storage)

(defn storage-path! [path]
  (def storage path))

(defn transact
  ([conn tx-data]
   (future
     (with-open [wrtr (writer storage :append true)]
       @(transact conn tx-data wrtr))))
  ([conn tx-data wrtr]
   (let [result (d/transact conn tx-data)]
     (future
       (let [{:keys [tx-data db-before] :as result} @result
             ident-or-eid #(or (:db/ident (d/entity db-before %)) %)
             datoms (for [[e a v _ add] tx-data]
                      [(ident-or-eid e)
                       (ident-or-eid a)
                       (if (ref? (d/db conn) a)
                         (ident-or-eid v)
                         v)
                       add])]
         (.write wrtr (prn-str datoms))
         result)))))

(defn- deserialize [{:keys [db eids] :as info} line]
  (let [datoms (clojure.edn/read-string {:readers {'datom identity}} line)
        [tx-datom datoms] ((juxt first rest) datoms)
        resolve-eid #(if (keyword? %)
                       %
                       (get eids (str %) (str %)))
        datoms (for [[e a v add] datoms]
                 [(if add :db/add :db/retract)
                  (resolve-eid e)
                  a
                  (if (ref? db a) (resolve-eid v) v)])
        tx (conj datoms {:db/id "datomic.tx"
                         :db/txInstant (nth tx-datom 2)})]
    tx))

(defn- update-info [info result]
  (-> info
      (update :eids merge (:tempids result))
      (assoc :db (:db-after result))))

(defn connect [db-uri schema data]
  (d/delete-database db-uri)
  (d/create-database db-uri)
  (let [conn (d/connect db-uri)
        tmp-storage (fs/temp-file "jobryant-datomic-api")]
    (doseq [tx [(expand-schema schema) data]]
      @(d/transact conn (conj tx
                              {:db/id "datomic.tx"
                               :db/txInstant #inst "2000-01-01T00:00:00.000-00:00"})))
    (with-open [rdr (reader storage)
                wrtr (writer tmp-storage)]
      (reduce
        (fn [info line]
          (let [tx (deserialize info line)
                result @(transact conn tx wrtr)]
            (update-info info result)))
        {:db (d/db conn)}
        (line-seq rdr)))
    (u/move tmp-storage storage)
    conn))