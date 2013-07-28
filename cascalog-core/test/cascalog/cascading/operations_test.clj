(ns cascalog.cascading.operations-test
  (:use clojure.test
        midje.sweet
        cascalog.cascading.operations
        cascalog.cascading.flow
        cascalog.cascading.tap
        cascalog.cascading.util)
  (:require [cascalog.cascading.io :as io]
            [cascalog.logic.fn :as serfn]
            [cascalog.cascading.types :refer (generator)]
            [cascalog.logic.algebra :as algebra]))

(defn produces [gen]
  (chatty-checker
   [query]
   (fact
     (sort (to-memory query)) => (sort gen))))

(defn square [x]
  (* x x))

(defn sum [& xs]
  (reduce + xs))

(deftest map-test
  (let [src (-> [["jenna" 10]
                 ["sam" 2]
                 ["oscar" 3]]
                (rename* ["user" "tweet-count"]))]
    (facts
      "Naming input and output the same replaces the input variable in
      the flow."
      (-> src
          (map* square "tweet-count" "tweet-count"))
      => (produces [["jenna" 100]
                    ["sam" 4]
                    ["oscar" 9]])

      "Using a new name appends the variable."
      (-> src
          (map* square "tweet-count" "squared"))
      => (produces [["jenna" 10 100]
                    ["sam" 2 4]
                    ["oscar" 3 9]]))))

(deftest inner-join-test
  (let [source (-> (generator [[1 2] [2 3] [3 4] [4 5]]))
        a      (-> source
                   (rename* ["a" "b"])
                   (filter* (serfn/fn [x] (> x 2)) "a")
                   (map* square "b" "c"))
        b      (-> source
                   (rename* ["a" "b"]))]
    (fact
      (cascalog-join [(->Inner a ["a" "b" "c"])
                      (->Inner b ["a" "b"])]
                     ["a" "b"])
      => (produces [[3 4 16] [4 5 25]]))))

(future-fact
 "Check that intermediates actually write out to their sequencefiles.")

(deftest check-intermediate-write
  (io/with-fs-tmp [_ tmp-a tmp-b]
    (fact "Interspersing calls to write doesn't affect the flow."
      (-> [[1 2] [2 3] [3 4] [4 5]]
          (rename* ["a" "b"])
          (write* (hfs-textline tmp-a))
          (map* #'inc "a" "inc")
          (filter* odd? "a")
          (map* square "inc" "squared")
          (map* dec "squared" "decreased")
          (write* (hfs-textline tmp-b)))
      => (produces [[1 2 2 4 3] [3 4 4 16 15]]))))

(deftest duplicate-inputs
  (io/with-fs-tmp [_ tmp-a tmp-b]
    (fact
      "duplicate inputs are okay, provided you sanitize them using
       with-duplicate-inputs. Note that with-dups won't currently
       clean up the extra delta vars for you. Fix this later by
       cleaning up the delta variables."
      (-> [[1 2] [2 3] [3 4] [4 5]]
          (rename* ["a" "b"])
          (with-duplicate-inputs ["a" "a" "b"]
            (fn [flow input delta]
              (-> flow (map* sum input "summed"))))
          (write* (hfs-textline tmp-a))
          (graph tmp-b))
      => (produces [[1 2 1 4]
                    [2 3 2 7]
                    [3 4 3 10]
                    [4 5 4 13]]))))

(deftest test-merged-flow
  (let [source (-> [[1 1] [2 2] [3 3] [4 4]]
                   (rename* ["a" "b"]))
        a      (-> source
                   (map* square "a" "c")
                   (map* dec "b" "d"))
        b      (-> source
                   (map* inc "a" "c")
                   (map* inc "b" "d"))]
    (fact "Merge combines streams without any join. This test forks a
           source, applies operations to each branch then merges the
           branches back together again."
      (algebra/sum [a b]) => (produces [[1 1 1 0]
                                        [2 2 4 1]
                                        [3 3 9 2]
                                        [4 4 16 3]
                                        [1 1 2 2]
                                        [2 2 3 3]
                                        [3 3 4 4]
                                        [4 4 5 5]]))))

(defn gt2 [x] (> x 2))

(deftest test-co-group
  (let [source (generator [[1 1] [2 2] [3 3] [4 4]])
        a      (-> source
                   (rename* ["a" "b"])
                   (filter* gt2 "a")
                   (map* square "b" "c"))
        b      (-> source
                   (rename* ["x" "y"]))]
    (fact "Join joins stuff"
      (-> (co-group* [a b] [["a"] ["x"]] ["a" "x" "b" "c" "y"]  [])
          (map* str "y" "q"))
      => (produces [[3 3 9 3 3 "3"] [4 4 16 4 4 "4"]]))

    (let [a (-> (generator [[1 1] [1 2] [2 2]]) (rename* ["a" "b"]))
          b (-> (generator [[1 10] [2 15]]) (rename* ["x" "y"]))
          q (co-group* [a b] [["a"] ["x"]] nil [((partial parallel-agg +) "y" "s")])]
      (fact "Agg after join"
        q => (produces [[1 1 20] [2 2 15]])))))

(deftest join-many-test
  (let [source (-> (generator [[1 1] [2 2] [3 3] [4 4]]))
        a      (-> source
                   (rename* ["a" "b"])
                   (filter* gt2 "a")
                   (map* square "b" "c"))
        b      (-> source
                   (rename* ["x" "a"]))]
    (fact "join many..."
      (-> (join-many [[a ["a"] :inner]
                      [b ["x"] :inner]]
                     ["a" "b" "y" "x" "c"])
          (map* str "y" "q"))
      => (produces [[3 3 9 3 3 "9"]
                    [4 4 16 4 4 "16"]]))))
