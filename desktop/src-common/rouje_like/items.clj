(ns rouje-like.items
  (:require [brute.entity :as br.e]
            [rouje-like.utils :as rj.u :refer [?]]
            [rouje-like.components :as rj.c]
            [rouje-like.entity-wrapper :as rj.e]
            [rouje-like.equipment :as rj.eq]
            [rouje-like.inventory :as rj.inv]
            [rouje-like.config :as rj.cfg]))

(defn remove-item
  [system pos type]
  (let [e-world (first (rj.e/all-e-with-c system :world))]
    (rj.u/update-in-world system e-world pos
                          (fn [entities]
                            (vec
                             (remove
                              #(#{type} (:type %))
                              entities))))))

(defn pickup-item
  [system e-by e-this [z x y] item-type]
  (let [c-broadcaster (rj.e/get-c-on-e system e-this :broadcaster)
        e-relay (first (rj.e/all-e-with-c system :relay))
        broadcast-pickup (fn [system]
                           (if (not (nil? c-broadcaster))
                             (rj.e/upd-c system e-relay :relay
                                         (fn [c-relay]
                                           (update-in c-relay [:static]
                                                      conj {:message (format "%s picked up %s"
                                                                             (let [by-c-broadcaster (rj.e/get-c-on-e system e-by :broadcaster)]
                                                                               ((:name-fn by-c-broadcaster) system e-by))
                                                                             ((:name-fn c-broadcaster) system e-this))
                                                            :turn (let [e-counter (first (rj.e/all-e-with-c system :counter))
                                                                        c-counter (rj.e/get-c-on-e system e-counter :counter)]
                                                                    (:turn c-counter))})))
                             system))]
    (case item-type
      :torch (let [c-playersight (rj.e/get-c-on-e system e-by :playersight)
                   sight-upper-bound (:upper-bound c-playersight)
                   sight-torch-multiplier (:torch-multiplier c-playersight)

                   c-torch (rj.e/get-c-on-e system e-this :torch)
                   torch-brightness (:brightness c-torch)

                   inc-sight (fn [prev] (if (<= prev (- sight-upper-bound torch-brightness))
                                          (+ prev (* torch-brightness sight-torch-multiplier))
                                          prev))]
               (-> system
                   (rj.e/upd-c e-by :playersight
                               (fn [c-playersight]
                                 (update-in c-playersight [:distance] inc-sight)))
                   (remove-item [z x y] item-type)
                   (broadcast-pickup)))

      :gold (as-> system system
              (rj.e/upd-c system e-by :wallet
                          (fn [c-wallet]
                            (update-in c-wallet [:gold]
                                       (partial + (:value (rj.e/get-c-on-e system e-this :gold))))))
              (remove-item system [z x y] item-type)
              (broadcast-pickup system))

      :health-potion (as-> system system
                           (rj.e/upd-c system e-by :inventory
                                       (fn [c-inventory]
                                         (update-in c-inventory [:hp-potion]
                                                    inc)))
                           (remove-item system [z x y] item-type)
                           (broadcast-pickup system))

      :equipment (as-> system system
                       (rj.inv/pickup-slot-item system e-by (rj.e/get-c-on-e system e-this :equipment))
                       (remove-item system [z x y] item-type)
                       (broadcast-pickup system))

      :purchasable (let [c-purchasable (rj.e/get-c-on-e system e-this :purchasable)
                         price (:value c-purchasable)
                         item (rj.e/get-c-on-e system e-this :equipment)
                         gold (:gold (rj.e/get-c-on-e system e-by :wallet))]
                     ;; check if the player has enough money
                     (if (< price gold)
                       ;; purchase the item
                       (as-> system system
                             (rj.inv/pickup-slot-item system e-by item)
                             (rj.e/upd-c system e-by :wallet
                                         (fn [c-wallet]
                                           (update-in c-wallet [:gold]
                                                      (partial + (- price)))))
                             (remove-item system [z x y] item-type)
                             (broadcast-pickup system))
                       ;; otherwise broadcast a message saying unable to purchase
                       system))

      system)))

(defn use-hp-potion
  [system e-by]
  (let [c-destructible (rj.e/get-c-on-e system e-by :destructible)
        max-hp (:max-hp c-destructible)
        hp (:hp c-destructible)
        hp-potion-val (:health rouje-like.config/potion-stats)]
    (as-> system system
          (if (> max-hp (+ hp hp-potion-val))
            (rj.e/upd-c system e-by :destructible
                        (fn [c-destructible]
                          (update-in c-destructible [:hp]
                                     (partial + (:health rj.cfg/potion-stats)))))
            (rj.e/upd-c system e-by :destructible
                        (fn [c-destructible]
                          (update-in c-destructible [:hp]
                                     (constantly max-hp)))))
          (rj.e/upd-c system e-by :inventory
                      (fn [c-inventory]
                        (update-in c-inventory [:hp-potion] dec))))))

(defn ^:private item>>world
  [system is-valid-tile? z item>>entities]
  (let [e-world (first (rj.e/all-e-with-c system :world))
        c-world (rj.e/get-c-on-e system e-world :world)
        levels (:levels c-world)
        world (nth levels z)]
    (loop [system system]
      (let [x (rand-int (count world))
            y (rand-int (count (first world)))]
        (if (is-valid-tile? world [x y])
          (rj.u/update-in-world system e-world [z x y]
                                 (fn [entities]
                                   (item>>entities entities)))
          (recur system))))))

(defn ^:private only-floor?
  [tile]
  (every? #(rj.cfg/<floors> (:type %))
          (:entities tile)))

(defn ^:private item>>entities
  [entities e-id e-type]
  (conj entities
        (rj.c/map->Entity {:id   e-id
                           :type e-type})))

(defn add-health-potion
  [{:keys [system z]}]
  (let [e-potion (br.e/create-entity)

        not-near-potions? (fn [world [x y]]
                            (rj.u/not-any-radially-of-type world [x y]
                                                           #(<= % 3) [:health-potion]))

        is-valid-tile? (fn [world [x y]]
                         (let [tile (get-in world [x y])]
                           (and (not-near-potions? world [x y])
                                (only-floor? tile))))

        health-potion>>entities (fn [entities]
                          (item>>entities entities e-potion :health-potion))
        system (item>>world system is-valid-tile? z
                            health-potion>>entities)]
    {:system (rj.e/system<<components
               system e-potion
               [[:item {:pickup-fn pickup-item}]
                [:broadcaster {:name-fn (constantly "a health potion")}]])
     :z z}))

(defn add-torch
  [{:keys [system z]}]
  (let [e-torch (br.e/create-entity)

        not-near-torches? (fn [world [x y]]
                            (rj.u/not-any-radially-of-type world [x y]
                                                           #(<= % 3) [:torch]))

        is-valid-tile? (fn [world [x y]]
                         (let [tile (get-in world [x y])]
                           (and (not-near-torches? world [x y])
                                (only-floor? tile))))

        torch>>entities (fn [entities]
                          (item>>entities entities e-torch :torch))
        system (item>>world system is-valid-tile? z
                            torch>>entities)]
    {:system (rj.e/system<<components
               system e-torch
               [[:item {:pickup-fn pickup-item}]
                [:torch {:brightness 2}]
                [:broadcaster {:name-fn (constantly "a torch")}]])
     :z z}))

(defn add-gold
  [{:keys [system z]}]
  (let [e-gold (br.e/create-entity)

        is-valid-tile? (fn [world [x y]]
                         (only-floor? (get-in world [x y])))

        gold>>entities (fn [entities]
                         (item>>entities entities e-gold :gold))
        system (item>>world system is-valid-tile? z
                            gold>>entities)]
    {:system (rj.e/system<<components
               system e-gold
               [[:item {:pickup-fn pickup-item}]
                [:gold {:value 1}]
                [:broadcaster {:name-fn
                               (fn [system e-this]
                                 (let [value (:value (rj.e/get-c-on-e system e-this :gold))]
                                   (str value " gold")))}]])
     :z z}))

(defn add-purchasable
  [system target-tile]
  (let [e-world (first (rj.e/all-e-with-c system :world))
        e-purchasable (br.e/create-entity)
        item (rj.eq/generate-random-equipment) ; right now just use equipment
        item-type (:type item)
        system (rj.u/update-in-world system e-world [(:z target-tile) (:x target-tile) (:y target-tile)]
                                     (fn [entities]
                                       (vec
                                        (conj
                                         (remove #(#{:wall} (:type %)) entities)
                                         (rj.c/map->Entity {:id   e-purchasable
                                                            :type :purchasable})))))]
    {:system (rj.e/system<<components
              system e-purchasable
              [[:item {:pickup-fn pickup-item}]
               [:purchasable {:value (rj.eq/equipment-value item)}]
               [:equipment {item-type item}]
               [:broadcaster {:name-fn (fn [system e-this]
                                         (let [value (:value (rj.e/get-c-on-e system e-this :purchasable))
                                               c-eq (rj.e/get-c-on-e system e-this :equipment)
                                               name (rj.eq/equipment-name (item-type c-eq))]
                                           (str "a " name " for " value " gold")))}]])
     :z (:z target-tile)}))

(defn add-equipment
  [{:keys [system z]}]
  (let [e-eq (br.e/create-entity)

        ;; generate a random equipment piece
        eq (rj.eq/generate-random-equipment)
        eq-type (:type eq)

        is-valid-tile? (fn [world [x y]]
                         (only-floor? (get-in world [x y])))

        eq>>entities (fn [entities]
                       (item>>entities entities e-eq :equipment))

        system (item>>world system is-valid-tile? z
                            eq>>entities)]
    {:system (rj.e/system<<components
              system e-eq
              [[:item {:pickup-fn pickup-item}]
               [:equipment {eq-type eq}]
               [:broadcaster {:name-fn
                              (fn [system e-this]
                                (let [eq-name (rj.eq/equipment-name (eq-type (rj.e/get-c-on-e system e-this :equipment)))]
                                  (str "a " eq-name)))}]])
     :z z}))

