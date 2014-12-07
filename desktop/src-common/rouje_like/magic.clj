(ns rouje-like.magic
  (require [rouje-like.utils :as rj.u :refer [?]]
           [rouje-like.config :as rj.cfg]
           [rouje-like.entity-wrapper :as rj.e]
           [rouje-like.components :as rj.c]
           [rouje-like.status-effects :as rj.stef]
           [rouje-like.messaging :as rj.msg]
           [brute.entity :as br.e]
           [rouje-like.destructible :as rj.d]))

(defn dec-mp
  [system e-this spell]
  (rj.e/upd-c system e-this :magic
              (fn [c-magic]
                (update-in c-magic [:mp]
                           #(- % (spell rj.cfg/spell->mp-cost))))))

(defn get-first-e-in-range
  [system distance direction world player-pos]
  (let [pos (rj.u/coords+offset player-pos (rj.u/direction->offset direction))]
    (loop [distance distance
           pos pos]
      (let [tile (get-in world pos nil)
            top-e (:id (rj.u/tile->top-entity tile))]
        (if (not (pos? distance))
          nil
          (if (rj.e/get-c-on-e system top-e :destructible)
            top-e
            (recur (dec distance) (rj.u/coords+offset pos (rj.u/direction->offset direction)))))))))

(defn use-fireball
  "E-THIS shoots a fireball in DIRECTION"
  [system e-this direction]
  (let [c-position (rj.e/get-c-on-e system e-this :position)
        e-this-pos  [(:x c-position) (:y c-position)]
        e-world (first (rj.e/all-e-with-c system :world))
        c-world (rj.e/get-c-on-e system e-world :world)
        levels (:levels c-world)
        world (nth levels (:z c-position))
        spell (:fireball rj.cfg/spell-effects)
        distance (:distance spell)
        damage (:value spell)]

    (as-> system system
          (dec-mp system e-this :fireball)
          (if-let [e-target (get-first-e-in-range system distance direction world e-this-pos)]
            (let [c-destructible (rj.e/get-c-on-e system e-target :destructible)]
              (as-> system system
                    (rj.msg/add-msg system :static (format "you shoot a fireball %s" (name direction)))
                    (let [e-fireball (br.e/create-entity)]
                      (rj.e/system<<components
                       system e-fireball
                       [[:fireball {}]
                        [:attacker {:status-effects [(assoc (:fireball rj.cfg/status-effects)
                                                            :e-from e-this
                                                            :apply-fn rj.stef/apply-burn)]}]
                        [:destructible {:hp             1000000
                                        :max-hp         1000000
                                        :status-effects []
                                        :def            10000
                                        :can-retaliate? false
                                        :take-damage-fn rj.d/take-damage}]
                        [:experience {:experience 0
                                      :level-up-fn (fn [e-this system]
                                                     system)}]
                        [:broadcaster {:name-fn (constantly "the fireball")}]]))
                    (let [e-fireball (first (rj.e/all-e-with-c system :fireball))]
                      (as-> system system
                            (rj.d/add-effects system e-target e-fireball)
                            (rj.c/take-damage c-destructible e-target damage e-fireball system)
                            (if (pos? (:experience (rj.e/get-c-on-e system e-fireball :experience)))
                              (->> (rj.e/upd-c system e-this :experience
                                               (fn [c-experience]
                                                 (update-in c-experience [:experience]
                                                            #(+ % (:experience (rj.e/get-c-on-e system e-fireball :experience))))))
                                   ((:level-up-fn (rj.e/get-c-on-e system e-this :experience)) e-this))
                              system)
                            (rj.e/kill-e system e-fireball)))))
            (rj.msg/add-msg system :static (format "you shoot a fireball %s, but it didn't hit anything"
                                                   (name direction)))))))

(defn use-powerattack
  "E-THIS has increased attack in DIRECTION for 1 turn"
  [system e-this direction]
  (let [c-position (rj.e/get-c-on-e system e-this :position)
        e-this-pos  [(:x c-position) (:y c-position)]
        e-world (first (rj.e/all-e-with-c system :world))
        c-world (rj.e/get-c-on-e system e-world :world)
        levels (:levels c-world)
        world (nth levels (:z c-position))
        spell (:powerattack rj.cfg/spell-effects)
        distance (:distance spell)
        damage (:value spell)]

    (as-> system system
          (dec-mp system e-this :powerattack)
          (if-let [e-target (get-first-e-in-range system distance direction world e-this-pos)]
            (let [c-destructible (rj.e/get-c-on-e system e-target :destructible)]
              (as-> system system
                    (rj.msg/add-msg system :static (format "you use power attack %s" (name direction)))
                      (as-> system system
                            (rj.e/upd-c system e-this :attacker
                                        (fn [c-attacker]
                                          (update-in c-attacker [:atk] + damage)))
                            (rj.c/take-damage c-destructible e-target (:atk (rj.e/get-c-on-e system e-this :attacker)) e-this system)
                            (rj.e/upd-c system e-this :attacker
                                        (fn [c-attacker]
                                          (update-in c-attacker [:atk] + (- 0 damage)))))))
            (rj.msg/add-msg system :static (format "you use power attack %s, but it didn't hit anything"
                                                   (name direction)))))))
