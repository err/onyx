(ns onyx.peer.transform
  (:require [clojure.core.async :refer [chan go alts!! close! >!] :as async]
            [onyx.peer.pipeline-extensions :as p-ext]
            [onyx.extensions :as extensions]
            [taoensso.timbre :refer [info]]
            [dire.core :refer [with-post-hook!]]))

(defn read-batch [queue consumers batch-size timeout]
  (let [consumer-chs (take (count consumers) (repeatedly #(chan 1)))]
    (doseq [[c consumer-ch] (map vector consumers consumer-chs)]
      (go (loop []
            (when-let [m (.receive c)]
              (extensions/ack-message queue m)
              (>! consumer-ch m)
              (recur)))))
    (let [chs (conj consumer-chs (async/timeout timeout))
          rets (doall (repeatedly batch-size #(first (alts!! chs))))]
      (doseq [ch chs] (close! ch))
      (filter identity rets))))

(defn decompress-segment [queue message]
  (let [segment (extensions/read-message queue message)]
    (read-string segment)))

(defn apply-fn [task segment]
  (let [user-ns (symbol (name (namespace (:onyx/fn task))))
        user-fn (symbol (name (:onyx/fn task)))]
    ((ns-resolve user-ns user-fn) segment)))

(defn compress-segment [segment]
  (pr-str segment))

(defn write-batch [queue session producers msgs]
  (for [p producers msg msgs]
    (extensions/produce-message queue p session msg)))

(defn read-batch-shim
  [{:keys [queue session ingress-queues batch-size timeout]}]
  (let [consumers (map (partial extensions/create-consumer queue session) ingress-queues)
        batch (read-batch queue consumers batch-size timeout)]
    {:batch batch :consumers consumers}))

(defn decompress-batch-shim [{:keys [queue batch]}]
  (let [decompressed-msgs (map (partial decompress-segment queue) batch)]
    {:decompressed decompressed-msgs}))

(defn apply-fn-shim [{:keys [decompressed task catalog]}]
  (let [task (first (filter (fn [entry] (= (:onyx/name entry) task)) catalog))
        results (map (partial apply-fn task) decompressed)]
    {:results results}))

(defn compress-batch-shim [{:keys [results]}]
  (let [compressed-msgs (map compress-segment results)]
    {:compressed compressed-msgs}))

(defn write-batch-shim [{:keys [queue egress-queues session compressed]}]
  (let [producers (map (partial extensions/create-producer queue session) egress-queues)
        batch (write-batch queue session producers compressed)]
    {:producers producers}))

(defmethod p-ext/read-batch :default
  [event] (read-batch-shim event))

(defmethod p-ext/decompress-batch :default
  [event] (decompress-batch-shim event))

(defmethod p-ext/apply-fn :default
  [event] (apply-fn-shim event))

(defmethod p-ext/compress-batch :default
  [event] (compress-batch-shim event))

(defmethod p-ext/write-batch :default
  [event] (write-batch-shim event))

(with-post-hook! #'read-batch-shim
  (fn [{:keys [batch consumers]}]
    (info "Transformer: Read batch of" (count batch) "segments from" (count consumers) "inputs")))

(with-post-hook! #'decompress-batch-shim
  (fn [{:keys [decompressed]}]
    (info "Transformer: Decompressed" (count decompressed) "segments")))

(with-post-hook! #'apply-fn-shim
  (fn [{:keys [results]}]
    (info "Transformer: Applied fn to" (count results) "segments")))

