(ns rouje-like.mobile
  (:require [rouje-like.entity :as rj.e]
            [rouje-like.utils :as rj.u]
            [rouje-like.world :as rj.wr]
            [rouje-like.components :as rj.c]))

(defn can-move?
  [_ _ target-tile _]
  (#{:floor :gold :torch} (:type (rj.u/get-top-entity target-tile))))

(defn move-player
  [_ e-this target-tile system]
  (let [c-sight (rj.e/get-c-on-e system e-this :sight)
        sight-decline-rate (:decline-rate c-sight)
        sight-lower-bound (:lower-bound c-sight)
        sight-upper-bound (:upper-bound c-sight)
        sight-torch-power (:torch-power c-sight)
        dec-sight (fn [prev] (if (> prev (inc sight-lower-bound))
                               (- prev sight-decline-rate)
                               prev))
        inc-sight (fn [prev] (if (<= prev (- sight-upper-bound sight-torch-power))
                               (+ prev sight-torch-power)
                               prev))

        c-position (rj.e/get-c-on-e system e-this :position)

        e-world (first (rj.e/all-e-with-c system :world))]
    (-> system
        (rj.e/upd-c e-this :moves-left
                    (fn [c-moves-left]
                      (update-in c-moves-left [:moves-left] dec)))

        (as-> system
              (case (:type (rj.u/get-top-entity target-tile))
                :gold  (-> system
                           (rj.e/upd-c e-this :gold
                                       (fn [c-gold]
                                         (update-in c-gold [:gold] inc)))
                           (rj.e/upd-c e-this :sight
                                       (fn [c-sight]
                                         (update-in c-sight [:distance] dec-sight))))
                :torch (-> system
                           (rj.e/upd-c e-this :sight
                                       (fn [c-sight]
                                         (update-in c-sight [:distance] inc-sight))))
                :floor (-> system
                           (rj.e/upd-c e-this :sight
                                       (fn [c-sight]
                                         (update-in c-sight [:distance] dec-sight))))
                system))

        (rj.wr/update-in-world e-world [(:x target-tile) (:y target-tile)]
                               (fn [entities _]
                                 (vec
                                   (conj
                                     (remove #(#{:gold :torch} (:type %))
                                             entities)
                                     (rj.c/map->Entity {:type :player
                                                        :id   e-this})))))
        (rj.wr/update-in-world e-world [(:x c-position) (:y c-position)]
                               (fn [entities _]
                                 (vec
                                   (remove
                                     #(#{:player} (:type %))
                                     entities))))

        (rj.e/upd-c e-this :position
                    (fn [c-position]
                      (-> c-position
                          (assoc-in [:x] (:x target-tile))
                          (assoc-in [:y] (:y target-tile))))))))

(defn move
  [_ e-this target-tile system]
  (let [c-position (rj.e/get-c-on-e system e-this :position)
        e-world (first (rj.e/all-e-with-c system :world))
        this-pos [(:x c-position) (:y c-position)]
        target-pos [(:x target-tile) (:y target-tile)]]
    (-> system
        (rj.wr/update-in-world e-world target-pos
                               (fn [entities _]
                                 (vec
                                   (conj entities
                                         (rj.c/map->Entity {:type :bat
                                                            :id   e-this})))))

        (rj.wr/update-in-world e-world this-pos
                               (fn [entities _]
                                 (vec
                                   (remove
                                     #(#{:bat} (:type %))
                                     entities))))

        (rj.e/upd-c e-this :position
                    (fn [c-position]
                      (-> c-position
                          (assoc-in [:x] (:x target-tile))
                          (assoc-in [:y] (:y target-tile))))))))