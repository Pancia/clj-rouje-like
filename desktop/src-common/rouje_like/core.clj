(ns rouje-like.core
  (:import [com.badlogic.gdx.scenes.scene2d.ui Label]
           [com.badlogic.gdx.graphics.g2d TextureRegion])

  (:require [play-clj.core :refer :all]
            [play-clj.ui   :refer :all]
            [play-clj.g2d  :refer :all]

            [brute.entity :as br.e]
            [brute.system :as br.s]

            [rouje-like.components :as rj.c]
            [rouje-like.entity     :as rj.e]
            [rouje-like.rendering  :as rj.r]
            [rouje-like.input      :as rj.in]
            [rouje-like.score      :as rj.sc]
            [rouje-like.player     :as rj.pl]
            [rouje-like.world      :as rj.wr]))

(declare main-screen rouje-like)

(def ^:private sys (atom 0))

(def ^:private block-size 27)
(def ^:private world-sizes [20 20])

(defn ^:private start
  [system]
  (let [e-board  (br.e/create-entity)
        e-player (br.e/create-entity)
        world (rj.wr/generate-random-world ;world's an atom!
                block-size world-sizes)]
    (-> system
        (rj.e/add-e e-player)
        (rj.e/add-c e-player (rj.c/->Player world))
        (rj.e/add-c e-player (rj.c/->Position (atom 10) (atom 10)))
        (rj.e/add-c e-player (rj.c/->MovesLeft (atom 100)))
        (rj.e/add-c e-player (rj.c/->Score (atom 0)))

        (rj.e/add-e e-board)
        (rj.e/add-c e-board (rj.c/->World world))
        (rj.e/add-c e-board (rj.c/->Renderable rj.wr/render-world!)))))

(defn ^:private register-system-fns
  [system]
  (-> system
      (rj.r/start)
      (br.s/add-system-fn  rj.r/process-one-game-tick)
      (br.s/add-system-fn rj.sc/process-one-game-tick)
      (br.s/add-system-fn rj.pl/process-one-game-tick)))

(defscreen main-screen
  :on-show
  (fn [screen _]
    (update! screen :renderer (stage) :camera (orthographic))
    (-> (br.e/create-system)
        (start)
        (register-system-fns)
        (as-> s (reset! sys s))))
  
  :on-render
  (fn [screen _]
    (clear!)
    (render! screen)
    (reset! sys
            (br.s/process-one-game-tick @sys
                                        (graphics! :get-delta-time))))

  ;; TODO: Maybe switch to on-key-up?
  :on-key-down
  (fn [screen _]
    (let [key-code (:key screen)]
      (rj.in/process-keyboard-input @sys key-code)))

  :on-fling
  (fn [screen _]
    (let [x-vel (:velocity-x screen)
          y-vel (:velocity-y screen)]
      (rj.in/process-fling-input @sys x-vel y-vel))))

(defgame rouje-like
  :on-create
  (fn [this]
    (set-screen! this main-screen)))