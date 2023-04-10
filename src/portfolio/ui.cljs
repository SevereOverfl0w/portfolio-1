(ns portfolio.ui
  (:require [portfolio.data :as data]
            [portfolio.homeless :as h]
            [portfolio.ui.actions :as actions]
            [portfolio.ui.canvas :as canvas]
            [portfolio.ui.canvas.background :as canvas-bg]
            [portfolio.ui.canvas.compare :as compare]
            [portfolio.ui.canvas.docs :as docs]
            [portfolio.ui.canvas.grid :as canvas-grid]
            [portfolio.ui.canvas.split :as split]
            [portfolio.ui.canvas.viewport :as canvas-vp]
            [portfolio.ui.canvas.zoom :as canvas-zoom]
            [portfolio.ui.client :as client]
            [portfolio.ui.collection :as collection]
            [portfolio.ui.search.index :as index]))

(def app (atom nil))

(defn get-collections [scenes collections]
  (->> (collection/get-default-organization (vals scenes) (vals collections))
       (map (juxt :id identity))
       (into {})))

(defn create-app [config canvas-tools extra-canvas-tools]
  (-> config
      (assoc :scenes @data/scenes)
      (assoc :collections (get-collections @data/scenes @data/collections))
      (assoc :views [(canvas/create-canvas
                      {:canvas/layout (:canvas/layout config)
                       :tools (into (or canvas-tools
                                        [(canvas-bg/create-background-tool config)
                                         (canvas-vp/create-viewport-tool config)
                                         (canvas-grid/create-grid-tool config)
                                         (canvas-zoom/create-zoom-tool config)
                                         (split/create-split-tool config)
                                         (docs/create-docs-tool config)
                                         (compare/create-compare-tool config)
                                         (split/create-close-tool config)])
                                    extra-canvas-tools)})])))

(def eventually-execute (h/debounce actions/execute-action! 250))

(defn index-content [app & [{:keys [ids]}]]
  (let [{:keys [index scenes collections]} @app]
    (when index
      (doseq [doc (cond->> (concat (vals scenes) (vals collections))
                    ids (filter (comp (set ids) :id)))]
        (println "Index" (:id doc))
        (index/index-document index doc)))))

(defn ->comparable [x]
  (dissoc x :updated-at :line :idx :component :component-fn :on-mount :on-unmount))

(defn get-diff-keys [m1 m2]
  (->> m1
       (filter (fn [[k v]]
                 (not= (->comparable (m2 k))
                       (->comparable v))))
       (map first)))

(defn start! [& [{:keys [on-render config canvas-tools extra-canvas-tools index]}]]
  (swap! app merge (create-app config canvas-tools extra-canvas-tools) {:index index})

  (add-watch data/scenes ::app
    (fn [_ _ old-scenes scenes]
      (let [collections (get-collections scenes (:collections @app))
            old-collections (get-collections old-scenes (:collections @app))]
        (swap! app (fn [state]
                     (-> state
                         (assoc :scenes scenes)
                         (assoc :collections collections))))
        (index-content app {:ids (concat
                                  (get-diff-keys scenes old-scenes)
                                  (get-diff-keys collections old-collections))}))
      (eventually-execute app [:go-to-current-location])))

  (add-watch data/collections ::app
    (fn [_ _ _ collections]
      (let [old-collections (:collections @app)
            collections (get-collections (:scenes @app) collections)]
        (swap! app assoc :collections collections)
        (index-content app {:ids (get-diff-keys old-collections collections)}))))

  (index-content app)
  (client/start-app app {:on-render on-render}))
