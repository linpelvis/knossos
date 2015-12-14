(ns knossos.linear.report
  "Constructs reports from a linear analysis.

  When a linear analyzer terminates it gives us a set of configs, each of which
  consists of a model, a final operation, and a set of pending operations. Our
  job is to render those operations like so:

              +---------+ +--------+
      proc 0  | write 1 | | read 0 |
              +---------+ +--------+
                    +---------+
      proc 1        | write 0 |
                    +---------+

      ------------ time ---------->"
  (:require [clojure.pprint :refer [pprint]]
            [clojure.set :as set]
            [knossos [history :as history]
                     [op :as op]
                     [core :as core]
                     [model :as model]]
            [analemma [xml :as xml]
                      [svg :as svg]]))

(defn ops
  "Computes distinct ops in an analysis."
  [analysis]
  (->> analysis
       :final-paths
       (mapcat (partial map :op))
       distinct))

(defn models
  "Computes distinct models in an analysis."
  [analysis]
  (->> analysis
       :final-paths
       (mapcat (partial map :model))
       distinct))

(defn model-numbers
  "A map which takes models and returns an integer."
  [models]
  (->> models (map-indexed (fn [i x] [x i])) (into {})))

(defn process-coords
  "Given a set of operations, computes a map of process identifiers to their
  track coordinates 0, 2, 4, ..."
  [ops]
  (->> ops
       history/processes
       history/sort-processes
       (map-indexed (fn [i process] [process i]))
       (into {})))

(defn time-bounds
  "Given a pair index and an analysis, computes the [lower, upper] bounds on
  times for rendering a plot."
  [pair-index analysis]
  [(dec (or (:index (history/invocation pair-index (:previous-ok analysis)))
            1))
   (inc (:index (history/completion pair-index (:op analysis))))])

(defn condense-time-coords
  "Takes time coordinates (a map of op indices to [start-time end-time]), and
  condenses times to remove sparse regions."
  [coords]
  (let [mapping (->> coords
                     vals
                     (apply concat)
                     (into (sorted-set))
                     (map-indexed (fn [i coord] [coord i]))
                     (into {}))]
    (->> coords
         (map (fn [[k [t1 t2]]]
                [k [(mapping t1) (mapping t2)]]))
         (into {}))))

(defn time-coords
  "Takes a pair index, time bounds, and a set of ops. Returns a map of op
  indices to logical [start-time end-time] coordinates."
  [pair-index [tmin tmax] ops]
  (->> ops
       (map (fn [op]
              (let [i   (:index op)
                    _   (assert i)
                    t1  (max tmin (:index (history/invocation pair-index op)))
                    t2  (or (:index (history/completion pair-index op))
                            tmax)]
                [i [(- t1 tmin)
                    (- t2 tmin)]])))
       (into {})
       condense-time-coords))

(defn path-bounds
  "Assign an initial :y, :min-x and :max-x to every transition in a path."
  [{:keys [time-coords process-coords]} path]
  (map (fn [transition]
         (let [op      (:op transition)
               [t1 t2] (time-coords (:index op))]
           (assoc transition
                  :y (process-coords (:process op))
                  :min-x t1
                  :max-x t2)))
       path))

(defn paths
  "Given time coords, process coords, and an analysis, emits paths with
  coordinate bounds."
  [analysis time-coords process-coords]
  (->> analysis
       :final-paths
       (map (partial path-bounds {:time-coords    time-coords
                                  :process-coords process-coords}))))

(def min-step 1/6)

(defn path->line
  "Takes a map of coordinates to models, path, a collection of lines, and emits
  a collection of lines with :min-x0, :max-x0, :y0, :min-x1, :max-x1, :y1
  coordinate ranges, and an attached model for the termination point. Assigns a
  :line-id to each transition for the line which terminates there. Returns a
  tuple [path' lines' bars'] where path' has line ids referring to indices in
  lines."
  [path lines models]
  (let [[path lines models] (reduce
                              (fn [[path lines models x0 y0] transition]
                                (let [y1 (:y transition)
                                      model (:model transition)
                                      x1 (loop [x1 (max (+ x0 min-step)
                                                        (:min-x transition))]
                                           (let [m (models {:x x1, :y y1})]
                                             (if (and m (not= m model))
                                               ; Taken
                                               (recur (+ x1 min-step))
                                               x1)))]
                                  (assert (<= x1 (:max-x transition))
                                          (str x1 " starting at " x0
                                               " is outside ["
                                               (:min-x transition)
                                               ", "
                                               (:max-x transition)
                                               "]\n"
                                               (with-out-str
                                                 (pprint path)
                                                 (pprint transition))))

                                  (if (nil? y0)
                                    ; First step
                                    [(conj path transition)
                                     lines
                                     models
                                     x1
                                     y1]
                                    ; Recurrence
                                    [(conj path (assoc transition
                                                       :line-id (count lines)))
                                     (conj lines {:id      (count lines)
                                                  :model   (:model transition)
                                                  :x0      x0
                                                  :y0      y0
                                                  :x1      x1
                                                  :y1      y1})
                                     (assoc models {:x x1 :y y1} model)
                                     x1
                                     y1])))
                              [[] lines models Double/NEGATIVE_INFINITY nil]
                              path)]
    [path lines models]))

(defn paths->initial-lines
  "Takes a collection of paths and returns:

  0. That collection of paths with each transition augmented with a :line-id
  1. A collection of lines indexed by line-id.
  2. A set of models, each with :x and :y coordinates."
  [paths]
  (reduce (fn [[paths lines models] path]
            (let [[path lines models] (path->line path lines models)]
              [(conj paths path) lines models]))
          [[] [] {}]
          paths))

(defn recursive-get
  "Looks up a key in a map recursively, by taking (get m k) and using it as a
  new key."
  [m k]
  (when-let [v (get m k)]
    (or (recursive-get m v)
        v)))

(defn collapse-mapping
  "Takes a map of x->x, where x may be a key in the map, and flattens it such
  that every key points directly to its final target."
  [m]
  (->> m
       keys
       (map (fn [k] [k (recursive-get m k)]))
       (into {})))

(defn merge-lines-r
  "Given [a set of lines, a mapping], and a group of overlapping lines, merges
  those lines, returning [lines mapping]."
  [[lines mapping] candidates]
  (if (empty? candidates)
    [lines mapping]
    ; Pretty easy; just drop every line but the first.
    (let [id0 (:id (first candidates))]
      (reduce (fn [[lines mapping] {:keys [id]}]
                [(assoc lines id nil)
                 (assoc mapping id id0)])
              [lines mapping]
              (next candidates)))))

(defn merge-lines
  "Takes an associative collection of line-ids to lines and produces a new
  collection of lines, and a map which takes the prior line IDs to new line
  IDs."
  [lines]
  (->> lines
       (group-by (juxt :x0 :y0 :x1 :y1 :model))
       vals
       (reduce merge-lines-r [lines {}])))

(defn paths->lines
  "Many path components are degenerate--they refer to equivalent state
  transitions. We want to map a set of paths into a smaller set of lines from
  operation to operation. We have coordinate bounds on every transition. Our
  job is to find non-empty intersections of those bounds for equivalent
  transitions and collapse them.

  We compute three data structures:

  - A collection of paths, each transition augmented with a numeric :line-id
  which identifies the line leading to that transition.
  - An indexed collection of line IDs to {:x0, y0, :x1, :y1} line segments
  connecting transitions.
  - A set of models, each with {:x :y :model} keys."
  [paths]
  (let [[paths lines models]  (paths->initial-lines paths)
        [lines mapping]       (merge-lines lines)
        lines           (into {} (map (juxt :id identity) (remove nil? lines)))
        mapping         (collapse-mapping mapping)
        paths           (map (fn [path]
                               (map (fn [transition]
                                      (assoc transition :line-id
                                             (mapping (:line-id transition))))
                                    path))
                               paths)]
    [paths lines models]))

(defn learnings
  "What a terrible function name. We should task someone with an action item to
  double-click down on this refactor.

  Basically we're taking an analysis and figuring out all the stuff we're gonna
  need to render it."
  [history analysis]
  (let [history         (history/index (history/complete history))
        pair-index      (history/pair-index history)
        ops             (ops analysis)
        models          (models analysis)
        model-numbers   (model-numbers models)
        process-coords  (process-coords ops)
        time-bounds     (time-bounds pair-index analysis)
        time-coords     (time-coords pair-index time-bounds ops)
        paths           (paths analysis time-coords process-coords )
        [paths lines bars] (paths->lines paths)]
    {:history       history
     :analysis      analysis
     :pair-index    pair-index
     :ops           ops
     :models        models
     :model-numbers model-numbers
     :process-coords process-coords
     :time-bounds   time-bounds
     :time-coords   time-coords
     :paths         paths
     :lines         lines
     :bars          models}))

(def process-height
  "How tall should an op be in process space?"
  0.6)

(defn hscale
  "Convert our units to horizontal CSS pixels"
  [x]
  (* x 150))

(defn vscale
  "Convert our units to vertical CSS pixels"
  [x]
  (* x 40))

(def type->color
  {:ok   "#B3F3B5"
   nil   "#F2F3B3"
   :fail "#F3B3B3"})

(defn op-color
  "What color should an op be?"
  [pair-index op]
  (-> pair-index
      (history/completion op)
      :type
      type->color))

(defn transition-color
  "What color should a transition be?"
  [transition]
  (if (model/inconsistent? (:model transition))
    "#C51919"
    "#000000"))

(defn render-ops
  "Given learnings, renders all operations as a group of SVG tags."
  [{:keys [time-coords process-coords pair-index ops]}]
  (->> ops
       (mapv (fn [op]
              (let [[t1 t2] (time-coords    (:index op))
                    p       (process-coords (:process op))
                    width   (- t2 t1)]
                (svg/group
                  (svg/rect (hscale t1)
                            (vscale p)
                            (vscale process-height)
                            (hscale width)
                            :rx (vscale 0.1)
                            :ry (vscale 0.1)
                            :fill (op-color pair-index op))
                  (-> (svg/text (str (name (:f op)) " "
                                     (pr-str (:value op))))
                      (xml/add-attrs :x (hscale (+ t1 (/ width 2.0)))
                                     :y (vscale (+ p (/ process-height
                                                        2.0))))
                      (svg/style :fill "#000000"
                                 :font-size (vscale (* process-height 0.6))
                                 :font-family "sans"
                                 :alignment-baseline :middle
                                 :text-anchor :middle))))))
       (apply svg/group)))

(defn render-lines
  "Given learnings, renders all lines as a group of SVG tags."
  [{:keys [lines]}]
  (->> lines
       vals
       (map (fn [{:keys [id x0 y0 x1 y1] :as line}]
              (prn line)
               (let [up? (< y0 y1)
                     y0  (if up? (+ y0 process-height) y0)
                     y1  (if up? y1 (+ y1 process-height))]
                 (prn :line line)
                 (svg/line (hscale x0) (vscale y0)
                           (hscale x1) (vscale y1)
                           :id            (str "line-" id)
                           :stroke-width  (vscale 0.025)
                           :stroke        (transition-color line)))))
       (apply svg/group)))

(defn hover-opacity
  "Takes an SVG element, makes it partially transparent, and adds onmouseover
  behavior raising its opacity."
  [e]
  (xml/add-attrs
    e
    :stroke-opacity "0.2"
    :onmouseover "this.setAttribute('stroke-opacity', '1.0');"
    :onmouseout  "this.setAttribute('stroke-opacity', '0.2');"))

(comment
(defn render-path
  "Renders a particular path, given learnings. Returns {:transitions, :models},
  each an SVG group. We render these separately because models go *on top* of
  ops, and transitions go *below* them."
  ([learnings path]
   (render-path learnings nil path [] []))
  ([{:keys [time-coords process-coords pair-index model-numbers] :as learnings}
    [prev-x prev-y]
    path
    transition-svgs
    model-svgs]
   (if (empty? path)
     ; Done
     {:models      (-> (apply svg/group model-svgs) hover-opacity)
      :transitions (-> (apply svg/group transition-svgs)
                       hover-opacity)}
     ; Handle this transition
     (let [[transition & path'] path
           op      (:op transition)
           model   (:model transition)
           x       (:x transition)
           y       (process-coords (:process op))
           ; A line from previous coords to current coords
           line    (when prev-x
                     ; Are we going up or down in process space?
                     (let [up? (< prev-y y)
                           y0  (if up? (+ prev-y process-height) prev-y)
                           y1  (if up? y      (+ y process-height))]
                       (svg/line (hscale prev-x) (vscale y0)
                                 (hscale x)      (vscale y1)
                                 :stroke-width (vscale 0.1)
                                 :stroke (transition-color transition))))
           transition-svgs    (if line
                                (conj transition-svgs line)
                                transition-svgs)
           ; A vertical line for the model
           bar      (svg/line (hscale x) (vscale y)
                             (hscale x) (vscale (+ y process-height))
                             :stroke-width (vscale 0.1)
                             :stroke (transition-color transition))
           ; A little illustration of the model state
           bubble   (svg/group
                      (-> (svg/text (str model))
                          (xml/add-attrs :x (hscale x)
                                         :y (vscale (- y 0.1)))
                          (svg/style :fill (transition-color transition)
                                     :font-size (vscale (* process-height 0.5))
                                     :font-family "sans"
                                     :alignment-baseline :bottom
                                     :text-anchor :middle)))
           model-svgs (conj model-svgs bar bubble)]
       (recur learnings [x y] path' transition-svgs model-svgs)))))

(defn render-paths
  "Renders all paths from learnings. Returns {:models ..., :transitions ...}"
  [learnings]
  (let [paths (->> learnings
                   :analysis
                   :final-paths
                   (map (partial path-bounds learnings))
                   relax-paths
                   (mapv (partial render-path learnings)))]
    {:models      (apply svg/group (map :models       paths))
     :transitions (apply svg/group (map :transitions  paths))})))

(defn svg-2
  "Emits an SVG 2 document."
  [& args]
  (let [svg-1 (apply svg/svg args)]
    (xml/set-attrs svg-1
                   (-> (xml/get-attrs svg-1)
                       (assoc "version" "1.0")))))

(defn render-analysis!
  "Render an entire analysis."
  [history analysis file]
  (let [learnings  (learnings history analysis)
        ops        (render-ops learnings)
;        paths      (render-paths learnings)
        lines      (render-lines learnings)]
    (spit file
          (xml/emit
            (svg-2
              (-> (svg/group
                    lines
                    ops)
                  (svg/translate (vscale 1) (vscale 1))))))))