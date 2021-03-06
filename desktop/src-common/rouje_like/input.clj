(ns rouje-like.input
  (:require [play-clj.core :as play]

            [rouje-like.entity-wrapper :as rj.e]
            [rouje-like.world :as rj.w]
            [rouje-like.utils :refer [?]]
            [rouje-like.utils :as rj.u]
            [rouje-like.player :as rj.pl]
            [rouje-like.components :as rj.c :refer [tick]]
            [rouje-like.messaging :as rj.msg]
            [rouje-like.inventory :as rj.inv]
            [rouje-like.items :as rj.item]
            [rouje-like.destructible :as rj.d]
            [rouje-like.config :as rj.cfg]
            [rouje-like.save-game :as rj.save]
            [rouje-like.magic :as rj.mag]

            [clojure.string :as s]
            [brute.entity]))

#_(in-ns 'rouje-like.input)
#_(use 'rouje-like.input :reload)

(def input-manager (atom {}))

(defn reset-input-manager!
  []
  (reset! input-manager {}))

(defn set-input-manager!
  [mode]
  (swap! input-manager
         assoc mode true))

(defn tick-entities
  [system]
  {:pre [(not (nil? system))]}
  (as-> system system
    ;;tick every entity, with those with a higher :pri going first
    (let [entities (rj.e/all-e-with-c system :tickable)
          e-player (first (rj.e/all-e-with-c system :player))
          c-position (rj.e/get-c-on-e system e-player :position)
          z (:z c-position)
          entities (filter #(if-let [c-position (rj.e/get-c-on-e system % :position)]
                              (= z (:z c-position))
                              true) ;;This is for the relay and the counter
                           entities)
          entities (reverse
                     (sort-by #(:pri (rj.e/get-c-on-e system % :tickable))
                              entities))]
      (reduce (fn [system entity]
                (let [c-tickable (rj.e/get-c-on-e system entity :tickable)]
                  (tick c-tickable entity system)))
              system entities))
    ;;reset all entities with energy that have less than they should
    ;;which is either their default-energy or 1
    ;;UNLESS it has negative energy, which indicates paralysis
    (let [energetic-entities (rj.e/all-e-with-c system :energy)]
      (reduce (fn [system entity]
                (rj.e/upd-c system entity :energy
                            (fn [{:keys [default-energy]
                                  :or {default-energy 1}
                                  :as c-energy}]
                              (update-in c-energy [:energy]
                                         #(cond
                                            (neg? %) 0

                                            (< % default-energy)
                                            default-energy

                                            :else %)))))
              system energetic-entities))))

(defn cmds->action
  [system cmds]
  ;;TODO: Either remove "race|class" cmds
  ;;  or make them debug & actually affect player stats
  (let [cmd->action {"race" (fn [system r]
                              (let [e-player (first (rj.e/all-e-with-c system :player))]
                                (rj.e/upd-c system e-player :race
                                            (fn [c-race]
                                              (assoc c-race :race
                                                     (keyword r))))))
                     "class" (fn [system c]
                               (let [e-player (first (rj.e/all-e-with-c system :player))]
                                 (rj.e/upd-c system e-player :class
                                             (fn [c-class]
                                               (assoc c-class :class
                                                      (keyword c))))))
                     "save"  (fn [system save-name]
                               (rj.save/save-game system save-name))
                     "load"  (fn [system save-name]
                               (rj.save/load-game system save-name))}
        cmd&arg (first (partition 2 (s/split cmds #" ")))
        action (cmd->action (first cmd&arg) (fn [s _] identity s))
        arg (second cmd&arg)]
    (action system arg)))

(def keycode->cmdl-action
  (let
    [k->cmdl-fn (fn [k] (fn [system]
                          (swap! rj.u/cmdl-buffer
                                 str (name k))
                          system))
     key-codes (range 29 55) ;[a-z]
     alphabet [:a :b :c :d :e :f :g :h :i :j :k :l :m
               :n :o :p :q :r :s :t :u :v :w :x :y :z]]
    (merge {(play/key-code :escape)    (fn [system]
                                         (reset-input-manager!)
                                         (reset! rj.u/cmdl-buffer "")
                                         system)
            (play/key-code :backspace) (fn [system]
                                         (swap! rj.u/cmdl-buffer #(apply str (drop-last 1 %)))
                                         system)
            (play/key-code :enter)     (fn [system]
                                         (let [cmds @rj.u/cmdl-buffer]
                                           (reset-input-manager!)
                                           (reset! rj.u/cmdl-buffer "")
                                           (cmds->action system cmds)))
            (play/key-code :space)     (fn [system] (swap! rj.u/cmdl-buffer str " ") system)}
           (zipmap key-codes (map k->cmdl-fn alphabet)))))

(def keycode->action
  {(play/key-code :semicolon)     (fn [system]
                                    (set-input-manager! :cmdl-mode?)
                                    system)
   (play/key-code :F)             (fn [system]
                                    (let [e-player (first (rj.e/all-e-with-c system :player))]
                                      (rj.e/upd-c system e-player
                                                  :player (fn [c-player]
                                                            (update-in c-player [:fog-of-war?]
                                                                       not)))))
   (play/key-code :E)             (fn [system]
                                    (let [e-player (first (rj.e/all-e-with-c system :player))]
                                      (rj.inv/equip-slot-item system e-player)))
   (play/key-code :num-1)         (fn [system]
                                    (reset-input-manager!)
                                    (set-input-manager! :spell-mode?)
                                    system)

   (play/key-code :enter)         (fn [system]
                                    (tick-entities system))
   (play/key-code :X)             (fn [system]
                                    (reset-input-manager!)
                                    (set-input-manager! :inspect-mode?)
                                    (rj.msg/add-msg! system
                                      "Inspecting... Please choose a direction."))
   (play/key-code :H)             (fn [system]
                                    (let [e-player (first (rj.e/all-e-with-c system :player))]
                                      (-> (rj.item/use-hp-potion system e-player)
                                          (tick-entities))))
   (play/key-code :M)             (fn [system]
                                    (let [e-player (first (rj.e/all-e-with-c system :player))]
                                      (-> (rj.item/use-mp-potion system e-player)
                                          (tick-entities))))})

(def keycode->direction
  {(play/key-code :W)          :up
   (play/key-code :dpad-up)    :up
   (play/key-code :K)          :up

   (play/key-code :S)          :down
   (play/key-code :dpad-down)  :down
   (play/key-code :J)          :down

   (play/key-code :A)          :left
   (play/key-code :dpad-left)  :left
   (play/key-code :H)          :left

   (play/key-code :D)          :right
   (play/key-code :dpad-right) :right
   (play/key-code :L)          :right})

(defn inspect
  [system direction]
  (let [e-player (first (rj.e/all-e-with-c system :player))
        e-world (first (rj.e/all-e-with-c system :world))
        {:keys [levels]} (rj.e/get-c-on-e system e-world :world)
        {:keys [x y z]} (rj.e/get-c-on-e system e-player :position)
        player-pos [x y]

        level (nth levels z)
        target-pos (rj.u/coords+offset player-pos (rj.u/direction->offset direction))
        target-tile (get-in level target-pos)

        {target-id :id} (rj.u/tile->top-entity target-tile)]
    (if-let [c-inspectable (rj.e/get-c-on-e system target-id :inspectable)]
      (let [{:keys [msg]} c-inspectable]
        (-> (rj.msg/clear-messages! system)
            (rj.msg/add-msg! msg)))
      (let [{:keys [type]
             :or {type "nothing"}} (rj.e/get-c-on-e system target-id :position)
            msg (str "Found " type)]
        (-> (rj.msg/clear-messages! system)
            (rj.msg/add-msg! msg))))))

(defn process-keyboard-input
  [system keycode]
  (let [direction (keycode->direction keycode)
        action (keycode->action keycode)]
    (cond
      (:cmdl-mode? @input-manager)
      (if-let [cmdl-action (keycode->cmdl-action keycode)]
        (cmdl-action system)
        system)

      action
      (action system)

      (:spell-mode? @input-manager)
      (as-> system system
        (do (reset-input-manager!) system)
        (rj.mag/cast-spell system direction)
        (tick-entities system))

      (:inspect-mode? @input-manager)
      (as-> system system
        (do (reset-input-manager!) system)
        (inspect system direction))

      direction
      (let [e-player (first (rj.e/all-e-with-c system :player))
            {:keys [energy]} (rj.e/get-c-on-e system e-player :energy)]
        (as-> system system
          ;;If we have energy tick the player,
          ;;otherwise we must have been paralyzed
          (if (pos? energy)
            (rj.pl/process-input-tick system direction)
            (if-let [c-broadcaster (rj.e/get-c-on-e system e-player :broadcaster)]
              (rj.msg/add-msg system
                (format "%s was paralyzed, and couldn't move this turn"
                        ((:name-fn c-broadcaster) system e-player)))
              system))
          ;;If we have more than 1 energy we should be able to go again
          (if (<= 1 (:energy (rj.e/get-c-on-e system e-player :energy)))
            system ;;ie: go again
            (tick-entities system))))

      :else
      system)))

(defn process-fling-input
  [system x-vel y-vel]
  (-> system
      (as-> system (if (< (* x-vel x-vel)
                          (* y-vel y-vel))
                     (if (pos? y-vel)
                       (rj.pl/process-input-tick system :down)
                       (rj.pl/process-input-tick system :up))
                     (if (pos? x-vel)
                       (rj.pl/process-input-tick system :right)
                       (rj.pl/process-input-tick system :left))))
      (tick-entities)))
