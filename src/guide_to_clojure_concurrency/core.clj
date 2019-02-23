(ns guide-to-clojure-concurrency.core
  (:require [clojure.core.async :refer [go chan] :as async])
  (:import (java.util.concurrent CountDownLatch LinkedBlockingQueue)
           (java.util Collection Date)))

(def resource (delay
                (println "Initialize resource")
                (CountDownLatch. 100)))
;;; delay
(defn demo-delay
  []
  (dotimes [x 100]
    (doto (Thread. ^Runnable (fn []
                               (println (format "In Thread %s" x))
                               (.countDown @resource)))
      (.start)))
  (.await @resource))

;;; promise
(def the-answer (promise))

(defn demo-promise
  []
  (doto (Thread. ^Runnable (fn [] (deliver the-answer 42)))
    (.start))
  (println @the-answer)
  )

;;; future
(defn demo-future
  []
  (let [another-answer (future 42)]
    (println @another-answer))
  )

;;; atom
(def donation-count (atom 0))
(def active true)

(defn demo-atom
  []
  (dotimes [_ 9]
    (doto (Thread. ^Runnable (fn []
                               (Thread/sleep 3000)
                               (swap! donation-count inc)
                               (println "Got one dollar")
                               (when active (recur))))
      (.start)))
  (let [latch (CountDownLatch. 1)]
    (doto (Thread. ^Runnable (fn []
                               (Thread/sleep (* 10 1000))
                               (println (str "We collected $" @donation-count " total!"))
                               (.countDown latch)))
      (.start))
    (.await latch)
    (def active false))
  )

;;; ref
(def total-donations (atom 0))
(def count-donations (atom 0))
(def donate-active true)

(defn demo-ref
  []
  (dotimes [_ 9]
    (doto (Thread. ^Runnable (fn []
                               (dosync
                                 (swap! total-donations + 10)
                                 (swap! count-donations inc)
                                 (println "Got ten dollar")
                                 )
                               (when donate-active (recur))))
      (.start)))
  (let [latch (CountDownLatch. 2)]
    (doto (Thread. ^Runnable (fn []
                               (Thread/sleep (* 10 1000))
                               (println (str "We collected $" @total-donations " total!"))
                               (.countDown latch)))
      (.start))
    (doto (Thread. ^Runnable (fn []
                               (Thread/sleep (* 10 1000))
                               (when (pos? @total-donations)
                                 (println (str "Average donation: $" (dosync (/ @total-donations @count-donations)))))
                               (.countDown latch)))
      (.start))
    (.await latch)
    (def donate-active false)
    )
  )

;;; agent

(def sum (agent 0))
(def numbers (range 0 (* 1000 1000)))

(defn demo-single-agent
  []
  (time (do (doseq [x numbers]
              (send sum + x))
            (await sum)
            (println @sum)
            (shutdown-agents)))
  )

(def sums (map agent (repeat 10 0)))
(defn demo-round-robin-agents
  []
  (time (do
          (doseq [[x sum] (map vector numbers (cycle sums))]
            (send sum + x)
            )
          (apply await-for (* 10 1000) sums)
          (println (apply + (map deref sums)))
          (shutdown-agents)))
  )

(def another-sums (map agent (repeat 10 0)))
(def number-queue (agent (range 0 (* 1000 1000))))
(declare dequeue-and-sum)
(defn demo-dequeue-from-agent
  []
  (time (do
          (doseq [sum-agent another-sums]
            (dequeue-and-sum sum-agent))
          (loop []
            (when (seq @number-queue)
              (Thread/sleep 1000)
              (recur)))
          (println (apply + (map deref another-sums)))
          (shutdown-agents)))
  )

(defn dequeue-and-sum
  [sum-agent]
  (letfn [(add [current-sum x]
            (let [new-sum (+ current-sum x)]
              (send number-queue dequeue)
              new-sum))
          (dequeue [xs]
            (when (seq xs)
              (send sum-agent add (first xs))
              (rest xs))
            )]
    (send number-queue dequeue))
  )

(def queue (LinkedBlockingQueue. ^Collection (range 0 (* 1000 1000))))
(def yet-another-sums (map agent (repeat 10 0)))
(defn- pull-and-add
  [sum-agent]
  (letfn [(add [current-sum x]
            (send sum-agent add (.take queue))
            (+ current-sum x))]
    (send sum-agent add (.take queue)))
  )

(defn demo-sum-with-real-queue
  []
  (time (do
          (doseq [sum yet-another-sums]
            (pull-and-add sum))
          (while (not= (.size queue) 0)
            (Thread/sleep 1000))
          (println (apply + (map deref yet-another-sums)))
          (shutdown-agents))))

;;; locking
(def lock-obj (Object.))
(defn sync-println
  [& args]
  (locking lock-obj (apply println args)))
(defn demo-locking
  []
  (let [latch (CountDownLatch. 20)]
    (println "Regular println>>>")
    (dotimes [_ 10]
      (doto (Thread. ^Runnable (fn []
                                 (println (str (Date.)))
                                 (.countDown latch)))
        (.start)))
    (Thread/sleep 1000)
    (println "Sync println>>>")
    (dotimes [_ 10]
      (doto (Thread. ^Runnable (fn []
                                 (sync-println (str (Date.)))
                                 (.countDown latch)))
        (.start)))
    (.await latch)
    )
  )

;;; core.async

(def num-chan (chan 100))
(def another-sum (atom 0))

(defn demo-core-async
  []
  (let [latch (CountDownLatch. 1000000)]
    (dotimes [_ 100]
      (go
        (loop []
          (let [number (async/<! num-chan)]
            (swap! another-sum + number)
            (.countDown latch)
            )
          (recur))))
    (go (doseq [x (range 1000000)]
          (async/>! num-chan x)))
    (.await latch)
    (println "Sum is " @another-sum)
    )
  )