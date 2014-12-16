(ns rouje-like.t-destructible
  (:use [midje.sweet]
        [rouje-like.test-utils]
        [rouje-like.destructible])
  (:require [rouje-like.entity-wrapper :as rj.e]
            [rouje-like.core :as rj.core]
            [rouje-like.snake :as rj.snk]
            [rouje-like.utils :refer [?]]
            [brute.entity :as br.e]))

(facts "add-effects & apply-effects"
       (let [system (as-> (start) system
                      (rj.snk/add-snake {:system system :z 1})
                      (:system system))
             e-player (first (rj.e/all-e-with-c system :player))
             e-snake (first (rj.e/all-e-with-c system :snake))
             system (add-effects system e-player e-snake)]
         (fact "add-effects: clean slate"
               e-snake  => truthy
               e-player => truthy
               (first
                 (filter #(#{:poison} (:type %))
                         (:status-effects
                           (rj.e/get-c-on-e system e-player :destructible))))
               => (contains {:type :poison}))
         (fact "add-effects: had poison, should upgrade"
               (let [system (rj.e/upd-c system e-snake :attacker
                                        (fn [c-attacker]
                                          (update-in c-attacker [:status-effects]
                                                     (fn [status-effects]
                                                       [(update-in (first status-effects) [:value] + 3)]))))
                     system (add-effects system e-player e-snake)]
                 (first
                   (filter #(#{:poison} (:type %))
                           (:status-effects
                             (rj.e/get-c-on-e system e-player :destructible))))
                 => (contains {:type :poison
                               :value 5})))
         (let [system (apply-effects system e-player)
               {:keys [hp max-hp]} (rj.e/get-c-on-e
                                     system e-player :destructible)]
           (fact "apply-effects: apply poison to player"
                 hp => (- max-hp 2)))))

(facts "take-damage"
       (let [system (start)
             system (:system
                      (rj.snk/add-snake {:system system
                                         :z 1}))
             e-player (first (rj.e/all-e-with-c system :player))
             c-destructible (rj.e/get-c-on-e system e-player :destructible)
             e-attacker (first (rj.e/all-e-with-c system :snake))
             c-attacker (rj.e/get-c-on-e system e-attacker :attacker)

             damage (inc (:atk c-attacker))
             system (take-damage c-destructible e-player damage e-attacker system)
             {:keys [hp max-hp]} (rj.e/get-c-on-e system e-player :destructible)]
         (fact "take-damage: lose hp"
               e-player => truthy
               e-attacker => truthy
               hp => (roughly (- max-hp damage) damage))
         (fact "take-damage: die"
               (let [system (rj.e/upd-c system e-player :destructible
                                        (fn [c-destructible]
                                          (assoc c-destructible :hp 1)))
                     c-destructible (rj.e/get-c-on-e system e-player :destructible)
                     system (take-damage c-destructible e-player damage e-attacker system)]
                 (rj.e/get-c-on-e system e-player :destructible)) => nil)))
