(ns rouje-like.spike-trap
  (:require [brute.entity :as br.e]

            [rouje-like.attacker :as rj.atk]
            [rouje-like.entity-wrapper :as rj.e]
            [rouje-like.components :as rj.c :refer [can-move? move
                                                    can-attack? attack]]
            [rouje-like.utils :as rj.u :refer [?]]
            [rouje-like.messaging :as rj.msg]
            [rouje-like.config :as rj.cfg]))

(declare process-input-tick)

(defn add-trap
  ([{:keys [system z]}]
   (let [e-world (first (rj.e/all-e-with-c system :world))
         c-world (rj.e/get-c-on-e system e-world :world)
         levels (:levels c-world)
         world (nth levels z)

         get-rand-tile (fn [world]
                         (get-in world [(rand-int (count world))
                                        (rand-int (count (first world)))]))]
     (loop [target-tile (get-rand-tile world)]
       (if (rj.cfg/<floors> (:type (rj.u/tile->top-entity target-tile)))
         (add-trap system target-tile)
         (recur (get-rand-tile world))))))

  ([system target-tile]
   (let [e-world (first (rj.e/all-e-with-c system :world))
         e-trap (br.e/create-entity)
         system (rj.u/update-in-world system e-world
                                      [(:z target-tile) (:x target-tile) (:y target-tile)]
                                      (fn [entities]
                                        (vec
                                          (conj
                                            (remove #(#{:wall} (:type %)) entities)
                                            (rj.c/map->Entity {:id   e-trap
                                                               :type :spike-trap})))))]
     {:system (add-trap system target-tile e-trap)
      :z (:z target-tile)}))

  ([system target-tile e-trap]
   (let [dir ({:down :up
                 :up   :down
                 :left :left
                 :right :right} (:dir (:extra (rj.u/tile->top-entity target-tile))))]
       (rj.e/system<<components
         system e-trap
         [[:spike-trap {}]
          [:position {:x    (:x target-tile)
                      :y    (:y target-tile)
                      :z    (:z target-tile)
                      :type :spike-trap}]
          [:attacker {:atk              (:atk (:spike-trap rj.cfg/trap->stats))
                      :can-attack?-fn   rj.atk/can-attack?
                      :attack-fn        rj.atk/attack
                      :status-effects   []
                      :is-valid-target? (partial #{:player})}]
          [:tickable {:tick-fn process-input-tick
                      :pri 0}]
          [:broadcaster {:name-fn (constantly (str "the "
                                                   (name :spike-trap)))}]]))))

(defn process-input-tick
  [_ e-this system]
  (let [c-position (rj.e/get-c-on-e system e-this :position)
        this-pos [(:x c-position) (:y c-position)]
        c-mobile (rj.e/get-c-on-e system e-this :mobile)

        e-world (first (rj.e/all-e-with-c system :world))
        c-world (rj.e/get-c-on-e system e-world :world)
        levels (:levels c-world)
        level (nth levels (:z c-position))

        c-sight (rj.e/get-c-on-e system e-this :sight)
        c-attacker (rj.e/get-c-on-e system e-this :attacker)

        player-is-adj? (seq (rj.u/get-neighbors-of-type level this-pos [:player]))]
    (if player-is-adj?
      (rj.msg/add-msg system :static
                            (format "%s hears a shuffling noise"
                                    (let [e-player (first (rj.e/all-e-with-c system :player))
                                          player-c-broadcaster (rj.e/get-c-on-e system e-player :broadcaster)]
                                      ((:name-fn player-c-broadcaster) system e-player))))
      (let [e-player (first (rj.e/all-e-with-c system :player))
            c-player-pos (rj.e/get-c-on-e system e-player :position)
            player-pos [(:x c-player-pos) (:y c-player-pos)]]
        (if (= this-pos player-pos)
          (let [target-tile (get-in level player-pos nil)
                e-target (:id (rj.u/tile->top-entity target-tile))]
            (cond
              (can-attack? c-attacker e-this e-target system)
              (attack c-attacker e-this e-target system)

              :else system))
          system)))))

