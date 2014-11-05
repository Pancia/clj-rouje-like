(ns rouje-like.world
  (:import [com.badlogic.gdx.graphics.g2d SpriteBatch TextureRegion]
           [clojure.lang Keyword Atom]
           [com.badlogic.gdx.graphics Texture Pixmap Color]
           [com.badlogic.gdx.files FileHandle])

  (:require [play-clj.g2d :refer :all]
            [play-clj.core :refer :all]

            [clojure.math.numeric-tower :as math]
            [brute.entity :as br.e]

            [rouje-like.components :as rj.c]
            [rouje-like.entity-wrapper :as rj.e]
            [rouje-like.utils :as rj.u :refer [?]]
            [rouje-like.items :as rj.items]
            [rouje-like.lichen :as rj.lc]
            [rouje-like.bat :as rj.bt]
            [rouje-like.skeleton :as rj.sk]
            [rouje-like.portal :as rj.p]
            [rouje-like.config :as rj.cfg]))

(defn ^:private block->freqs
  [block]
  (frequencies
    (map (fn [tile]
           (:type (rj.u/tile->top-entity tile)))
         block)))

(defn ^:private get-smoothed-tile
  [block-d1 block-d2 x y z]
  (let [wall-threshold-d1 5
        wall-bound-d2 2
        d1-block-freqs (block->freqs block-d1)
        d2-block-freqs (if (nil? block-d2)
                         {:wall (inc wall-bound-d2)}
                         (block->freqs block-d2))
        wall-count-d1 (get d1-block-freqs :wall 0)
        wall-count-d2 (get d2-block-freqs :wall 0)
        result (if (or (>= wall-count-d1 wall-threshold-d1)
                       (<= wall-count-d2 wall-bound-d2))
                 :wall
                 :floor)]
    (update-in (rj.c/map->Tile {:x x :y y :z z
                                :entities [(rj.c/map->Entity {:id   nil
                                                              :type :floor})]})
               [:entities] (fn [entities]
                             (if (= result :wall)
                               (conj entities
                                     (rj.c/map->Entity {:id   nil
                                                        :type :wall}))
                               entities)))))

(defn ^:private get-smoothed-col
  [level [x z] max-dist]
  {:pre [(#{1 2} max-dist)]}
  (mapv (fn [y]
          (get-smoothed-tile
            (rj.u/get-ring-around level [x y] 1)
            (if (= max-dist 2)
              (rj.u/get-ring-around level [x y] 2)
              nil)
            x y z))
        (range (count (first level)))))

(defn ^:private smooth-level-v1
  [{:keys [level z]}]
  {:level (mapv (fn [x]
                  (get-smoothed-col level [x z] 2))
                (range (count level)))
   :z z})

(defn ^:private smooth-level-v2
  [{:keys [level z]}]
  {:level (mapv (fn [x]
                  (get-smoothed-col level [x z] 1))
                (range (count level)))
   :z z})

(def ^:private init-wall% 45)
(def ^:private init-torch% 2)
(def ^:private init-gold% 5)
(def ^:private init-lichen% 1)
(def ^:private init-bat% 1)
(def ^:private init-skeleton% 1)

(defn init-entities
  [system z]
  (-> system
      ;; Add Items: Gold, Torches...
      (as-> system
        (do (println "core::add-gold: " (not (nil? system))) system)
        (nth (iterate rj.items/add-gold {:system system :z z})
             (* (/ init-gold% 100)
                (apply * (vals rj.cfg/world-sizes))))
        (:system system))
      (as-> system
        (do (println "core::add-torch " (not (nil? system))) system)
        (nth (iterate rj.items/add-torch {:system system :z z})
             (* (/ init-torch% 100)
                (apply * (vals rj.cfg/world-sizes))))
        (:system system))

      ;; Spawn lichens
      (as-> system
        (do (println "core::add-lichen " (not (nil? system))) system)
        (nth (iterate rj.lc/add-lichen {:system system :z z})
             (* (/ init-lichen% 100)
                (apply * (vals rj.cfg/world-sizes))))
        (:system system))

      ;; Spawn bats
      (as-> system
        (do (println "core::add-bat " (not (nil? system))) system)
        (nth (iterate rj.bt/add-bat {:system system :z z})
             (* (/ init-bat% 100)
                (apply * (vals rj.cfg/world-sizes))))
        (:system system))

      ;; Spawn Skeletons
      (as-> system
        (do (println "core::add-skeleton " (not (nil? system))) system)
        (nth (iterate rj.sk/add-skeleton {:system system :z z})
             (* (/ init-skeleton% 100)
                (apply * (vals rj.cfg/world-sizes))))
        (:system system))))

(defn add-portal
  [system z]
  ;; Add portal
  (as-> system system
    (do (println "core::add-portal " (not (nil? system))) system)
    (rj.p/add-portal {:system system :z z})
    (:system system)))

(defn generate-random-level
  ([level-sizes z]
   (let [world-types [:cave :desert]]
     (generate-random-level level-sizes z (rand-nth world-types))))

  ([{:keys [width height]} z world-type]
   (case world-type
     :cave (let [level (vec
                         (map vec
                              (for [x (range width)]
                                (for [y (range height)]
                                  (update-in (rj.c/map->Tile {:x x :y y :z z
                                                              :entities [(rj.c/map->Entity {:id   nil
                                                                                            :type :floor})]})
                                             [:entities] (fn [entities]
                                                           (if (< (rand-int 100) init-wall%)
                                                             (conj entities
                                                                   (rj.c/map->Entity {:id   nil
                                                                                      :type :wall}))
                                                             entities)))))))]
             ;; SMOOTH-WORLD
             (as-> level level
               (nth (iterate smooth-level-v1 {:level level
                                              :z z})
                    4)
               (:level level)
               (nth (iterate smooth-level-v2 {:level level
                                              :z z})
                    2)
               (:level level)))
     :desert (vec
               (map vec
                    (for [x (range width)]
                      (for [y (range height)]
                        (rj.c/map->Tile {:x x :y y :z z
                                         :entities [(rj.c/map->Entity {:id   nil
                                                                       :type :dune})]}))))))))

(declare render-world add-level)
(defn init-world
  [system]
  (let [z 0
        e-world  (br.e/create-entity)
        level0 (generate-random-level
                 rj.cfg/world-sizes z)
        level1 (generate-random-level
                 rj.cfg/world-sizes (inc z))]
    (-> system
        (rj.e/add-e e-world)
        (rj.e/add-c e-world (rj.c/map->World {:levels [level0 level1]
                                              :add-level-fn add-level}))
        (init-entities z)
        (add-portal z)
        (init-entities (inc z))

        (rj.e/add-c e-world (rj.c/map->Renderable {:render-fn render-world
                                                   :args      {:view-port-sizes rj.cfg/view-port-sizes}})))))

(defn add-level
  [system z]
  (let [e-world (first (rj.e/all-e-with-c system :world))
        new-level (generate-random-level rj.cfg/world-sizes z)]
    (-> system
        (rj.e/upd-c e-world :world
                    (fn [c-world]
                      (update-in c-world [:levels]
                                 (fn [levels]
                                   (conj levels
                                         new-level)))))
        (init-entities z)
        (add-portal (dec z)))))

(def ^:private type->tile-info
  (let [grim-tile-sheet "grim_12x12.png"
        darkond-tile-sheet "DarkondDigsDeeper_16x16.png"]
    {:player   {:x 0 :y 4
                :width 12 :height 12
                :color {:r 255 :g 255 :b 255 :a 255}
                :tile-sheet grim-tile-sheet}
     :wall     {:x 3 :y 2
                :width 12 :height 12
                :color {:r 255 :g 255 :b 255 :a 128}
                :tile-sheet grim-tile-sheet}
     :gold     {:x 1 :y 9
                :width 12 :height 12
                :color {:r 255 :g 255 :b 1 :a 255}
                :tile-sheet grim-tile-sheet}
     :lichen   {:x 15 :y 0
                :width 12 :height 12
                :color {:r 1 :g 255 :b 1 :a 255}
                :tile-sheet grim-tile-sheet}
     :floor    {:x 14 :y 2
                :width 12 :height 12
                :color {:r 255 :g 255 :b 255 :a 64}
                :tile-sheet grim-tile-sheet}
     :torch    {:x 1 :y 2
                :width 12 :height 12
                :color {:r 255 :g 1 :b 1 :a 255}
                :tile-sheet grim-tile-sheet}
     :portal   {:x 4 :y 9
                 :width 12 :height 12
                 :color {:r 102 :g 0 :b 102 :a 255}
                 :tile-sheet grim-tile-sheet}
     :bat      {:x 14 :y 5
                :width 12 :height 12
                :color {:r 255 :g 255 :b 255 :a 128}
                :tile-sheet grim-tile-sheet}
     :skeleton {:x 3 :y 5
                :width 16 :height 16
                :color {:r 255 :g 255 :b 255 :a 255}
                :tile-sheet darkond-tile-sheet}
     :dune     {:x 14 :y 2
                :width 12 :height 12
                :color {:r 255 :g 140 :b 1 :a 255}
                :tile-sheet grim-tile-sheet}}))

(def ^:private type->texture
  (memoize
    (fn [^Keyword type]
      (let [tile-info (type->tile-info type)
            tile-sheet (:tile-sheet tile-info)
            width (:width tile-info)
            height (:height tile-info)
            x (* width (:x tile-info))
            y (* height (:y tile-info))
            tile-color (:color tile-info)]
        (assoc (texture tile-sheet
                        :set-region x y width height)
               :color tile-color)))))

(defn render-world
  [_ e-this {:keys [view-port-sizes]} system]
  (let [e-player (first (rj.e/all-e-with-c system :player))

        c-player-pos (rj.e/get-c-on-e system e-player :position)
        player-pos [(:x c-player-pos)
                    (:y c-player-pos)]
        show-world? (:show-world? (rj.e/get-c-on-e system e-player :player))

        c-sight (rj.e/get-c-on-e system e-player :playersight)
        sight (math/ceil (:distance c-sight))

        c-world (rj.e/get-c-on-e system e-this :world)
        levels (:levels c-world)
        world (nth levels (:z c-player-pos))

        [vp-size-x vp-size-y] view-port-sizes

        start-x (max 0 (- (:x c-player-pos)
                          (int (/ vp-size-x 2))))
        start-y (max 0 (- (:y c-player-pos)
                          (int (/ vp-size-y 2))))

        end-x (+ start-x vp-size-x)
        end-x (min end-x (count world))

        end-y (+ start-y vp-size-y)
        end-y (min end-y (count (first world)))

        start-x (- end-x vp-size-x)
        start-y (- end-y vp-size-y)

        renderer (new SpriteBatch)]
    (.begin renderer)
    (doseq [x (range start-x end-x)
            y (range start-y end-y)
            :let [tile (get-in levels [(:z c-player-pos) x y])]]
      (when (or show-world?
                (rj.u/can-see? world sight player-pos [x y]))
        (let [texture-entity (-> (rj.u/tile->top-entity tile)
                                 (:type) (type->texture))]
          (let [color-values (:color texture-entity)]
            (.setColor renderer
                       (Color. (float (/ (:r color-values) 255))
                               (float (/ (:g color-values) 255))
                               (float (/ (:b color-values) 255))
                               (float (/ (:a color-values) 255)))))
          (.draw renderer
                 (:object texture-entity)
                 (float (* (+ (- x start-x)
                              (:left rj.cfg/padding-sizes))
                           rj.cfg/block-size))
                 (float (* (+ (- y start-y)
                              (:btm rj.cfg/padding-sizes))
                           rj.cfg/block-size))
                 (float rj.cfg/block-size) (float rj.cfg/block-size)))))
    (.end renderer)))

