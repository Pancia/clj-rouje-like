(ns rouje-like.weapons
  (:require [rouje-like.config :as rj.cfg]
            [rouje-like.entity-wrapper :as rj.e]))

(def weapon-qualities [{:name :quick :stats {:atk 1}}
                        {:name :giant :stats {:atk 2}}
                        {:name :great :stats {:atk 2}}
                        {:name :tiny :stats {:atk 1}}
                        {:name :dull :stats {:atk -1}}
                        {:name :dented :stats {:atk -2}}])

(def weapons [{:name :sword :stats {:atk 1}}
              {:name :mace :stats {:atk 1}}
              {:name :axe :stats {:atk 1}}
              {:name :flail :stats {:atk 1}}
              {:name :dagger :stats {:atk 1}}])

(def weapon-effects [{:name :bloodletting}
                     {:name :pain}
                     {:name :poison}
                     {:name :paralysis}
                     {:name :power}
                     {:name :death}
                     nil])

(def armor [{:name :breastplate :stats {:max-hp 3}}])

(defn generate-random-weapon []
  "Generate a random weapon consisting of a weapon quality, a weapon type,
   and a weapon effect."
  (let [quality (rand-nth weapon-qualities)
        wpn (rand-nth weapons)
        effect (rand-nth weapon-effects)]
    (if effect
      [quality wpn {:name :of} effect] ; lame fix
      [quality wpn])))

(defn generate-random-armor []
  [(rand-nth armor)])

(defn reduce-stats [stat-lst]
  "Takes list of stats and reduces them to a single map with all stats
   combined."
  (reduce (fn [r stats]
            (merge-with + r stats)) {} stat-lst))

(defn equipment-name [eq]
  "Return a string containing the name of EQ."
  (if eq
    (reduce (fn [sym1 sym2]
              (str (name sym1) " " (name sym2))) ""
            (map :name eq))
    ""))

(defn equipment-stats [eq]
  "Return a map of the stats of EQ."
  (and eq
       (reduce-stats (filter (fn [elt] (not (nil? elt)))
                             (map :stats eq)))))

(defn update-stat [system e-this stat amount]
  "Update the statistic STAT by AMOUNT on E-THIS."
  (let [comp-to-update (rj.cfg/stat->comp stat)]
    (rj.e/upd-c system e-this comp-to-update
                (fn [c-comp]
                  (update-in c-comp [stat] (partial + amount))))))

(defn update-stats [system e-this stats]
  "Loop over [stat val] pairs in STATS and update them on E-THIS."
  (if (not (empty? stats))
    (loop [system system
           e-this e-this
           stats stats]
      (let [[stat amount] (first stats)
            more? (rest stats)]
        (if (empty? more?)
          (update-stat system e-this stat amount)
          (recur (update-stat system e-this stat amount) e-this more?))))
    system))

(defn negate-stats [stats]
  "Return a map of STATS w/ the stat values negated."
  (if (not (empty? stats))
    (reduce (fn [r [stat val]]
              (assoc r stat (apply - val '())))
            {} stats)
    nil))

(defn switch-equipment [system e-this eq-type new-eq]
  "Switch the current equipment slot of EQ-TYPE on E-THIS with NEW-EQ and update
   E-THIS's stats to reflect the change."
  (let [current-eq (eq-type (rj.e/get-c-on-e system e-this eq-type))
        old-stats (equipment-stats current-eq)
        new-stats (equipment-stats new-eq)]
    (-> system
          ;; iterate over old-stats and pass them to update-stat for removal
          (update-stats e-this (negate-stats old-stats))
          ;; switch equipment
          (rj.e/upd-c e-this eq-type
                      (fn [c-eq]
                        (assoc-in c-eq [eq-type] new-eq)))
          ;; iterate over new-stats and pass them to update-stat
          (update-stats e-this new-stats))))
