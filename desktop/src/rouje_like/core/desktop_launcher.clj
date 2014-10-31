(ns rouje-like.core.desktop-launcher
  (:require [rouje-like.core :refer [rouje-like]]
            [rouje-like.config :refer [block-size padding-sizes view-port-sizes]]
            [clojure.tools.nrepl.server :as nrepl-server])
  (:import [com.badlogic.gdx.backends.lwjgl LwjglApplication]
           [org.lwjgl.input Keyboard])
  (:gen-class))

(defn -main
  []
  (LwjglApplication. rouje-like "rouje-like"
                     (* (+ (+ (:left padding-sizes)
                              (:right padding-sizes))
                           (view-port-sizes 0))
                        block-size)
                     (* (+ (+ (:btm padding-sizes)
                              (:top padding-sizes))
                           (view-port-sizes 1))
                        block-size))
  (Keyboard/enableRepeatEvents true))
