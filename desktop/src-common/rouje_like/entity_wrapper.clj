(ns rouje-like.entity-wrapper
  (:import [clojure.lang Keyword])
  (:require [brute.entity :as br.e]
            [rouje-like.components :as rj.c]
            [clojure.string :as s]))

(defn all-e-with-c
  [system ^Keyword component]
  (br.e/get-all-entities-with-component system (rj.c/get-type component)))

(defn get-c-on-e
  [system entity ^Keyword component]
  (br.e/get-component system entity (rj.c/get-type component)))

(defn add-e
  [system entity]
  (br.e/add-entity system entity))

(defn add-c
  [system entity c-instance]
  (br.e/add-component system entity c-instance))

(defn upd-c
  [system entity ^Keyword component fn]
  (br.e/update-component system entity (rj.c/get-type component) fn))

(defn kill-e
  [system entity]
  (br.e/kill-entity system entity))

(defn all-c-on-e
  [system entity]
  (br.e/get-all-components-on-entity system entity))

(defn ->CamelCase
  [k]
  (str (s/upper-case (first k))
       (s/replace (apply str (rest k)) #"-(\w)" #(.toUpperCase (%1 1)))))

(defmacro keyword->new-component
  [k#]
  (symbol "rouje-like.components" (str "map->" (->CamelCase (name k#)))))

(defmacro partition->add-c
  [s# e-this# k-component# m-component#]
  `(add-c ~s# ~e-this# ((keyword->new-component ~k-component#) ~m-component#)))

(defmacro system<<components
  [s e-this partitions]
  `(let [s# ~s]
     (as-> s# ~'s
       ~@(for [p partitions]
           `(partition->add-c ~'s ~e-this ~(p 0) ~(p 1))))))

