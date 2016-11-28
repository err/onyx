(ns onyx.messaging.aeron.subscriber
  (:require [onyx.messaging.protocols.subscriber :as sub]
            [onyx.messaging.protocols.status-publisher :as status-pub]
            [onyx.messaging.common :as common]
            [onyx.messaging.aeron.utils :as autil :refer [action->kw stream-id heartbeat-stream-id]]
            [onyx.compression.nippy :refer [messaging-compress messaging-decompress]]
            [onyx.static.default-vals :refer [arg-or-default]]
            [onyx.types :refer [barrier? message? heartbeat? ->Heartbeat ->ReadyReply ->BarrierAlignedDownstream]]
            [taoensso.timbre :refer [info warn] :as timbre])
  (:import [java.util.concurrent.atomic AtomicLong]
           [org.agrona.concurrent UnsafeBuffer]
           [org.agrona ErrorHandler]
           [io.aeron Aeron Aeron$Context Publication Subscription Image ControlledFragmentAssembler UnavailableImageHandler AvailableImageHandler]
           [io.aeron.logbuffer ControlledFragmentHandler ControlledFragmentHandler$Action]))

;; FIXME to be tuned
(def fragment-limit-receiver 1000)

(defn ^java.util.concurrent.atomic.AtomicLong lookup-ticket [ticket-counters src-peer-id session-id]
  (-> ticket-counters
      (swap! update-in 
             [src-peer-id session-id]
             (fn [ticket]
               (or ticket (AtomicLong. -1))))
      (get-in [src-peer-id session-id])))

(defn assert-epoch-correct! [epoch message-epoch message]
  (when-not (= (inc epoch) message-epoch)
    (throw (ex-info "Unexpected barrier found. Possibly a misaligned subscription."
                    {:message message
                     :epoch epoch}))))

(defn invalid-replica-found! [replica-version message]
  (throw (ex-info "Shouldn't have received a message for this replica-version as we have not sent a ready message." 
                  {:replica-version replica-version 
                   :message message})))

(defn unavailable-image [sub-info]
  (reify UnavailableImageHandler
    (onUnavailableImage [this image] 
      (info "UNAVAILABLE image " (.position image) " " (.sessionId image) " " sub-info))))

(defn available-image [sub-info]
  (reify AvailableImageHandler
    (onAvailableImage [this image] 
      (info "AVAILABLE image " (.position image) " " (.sessionId image) " " sub-info))))

;; One subscriber has multiple status pubs, one for each publisher
;; this moves the reconciliation into the subscriber itself
;; Have a status publisher type
;; Containing src-peer / src-site

(deftype StatusPublisher [peer-config peer-id dst-peer-id site ^Aeron conn ^Publication pub 
                          ^:unsynchronized-mutable blocked ^:unsynchronized-mutable completed
                          ^:unsynchronized-mutable session-id ^:unsynchronized-mutable heartbeat]
  status-pub/PStatusPublisher
  (start [this]
    (let [media-driver-dir (:onyx.messaging.aeron/media-driver-dir peer-config)
          status-error-handler (reify ErrorHandler
                                 (onError [this x] 
                                   (info "Aeron status channel error" x)
                                   ;(System/exit 1)
                                   ;; FIXME: Reboot peer
                                   (taoensso.timbre/warn x "Aeron status channel error")))
          ctx (cond-> (Aeron$Context.)
                error-handler (.errorHandler status-error-handler)
                media-driver-dir (.aeronDirectoryName ^String media-driver-dir))
          channel (autil/channel (:address site) (:port site))
          conn (Aeron/connect ctx)
          pub (.addPublication conn channel heartbeat-stream-id)]
      (StatusPublisher. peer-config peer-id dst-peer-id site conn pub blocked completed nil nil)))
  (stop [this]
    (.close conn)
    (.close pub)
    (StatusPublisher. peer-config peer-id dst-peer-id site nil nil false false nil nil))
  (info [this]
    {:INFO :TODO})
  (set-session-id! [this sess-id]
    (assert (or (nil? session-id) (= session-id sess-id)))
    (set! session-id sess-id)
    this)
  (set-heartbeat! [this]
    (set! heartbeat (System/currentTimeMillis))
    this)
  (block! [this]
    (assert (false? blocked))
    (set! blocked true)
    this)
  (unblock! [this]
    (set! blocked false))
  (blocked? [this]
    blocked)
  (set-completed! [this completed?]
    (set! completed completed?))
  (completed? [this]
    completed)
  (new-replica-version! [this]
    (set! blocked false)
    (set! completed false)
    this)
  (offer-heartbeat! [this replica-version epoch]
    (let [msg (->Heartbeat replica-version peer-id epoch)
          payload ^bytes (messaging-compress msg)
          buf ^UnsafeBuffer (UnsafeBuffer. payload)
          ret (.offer ^Publication pub buf 0 (.capacity buf))] 
      (info "Offer heartbeat subscriber:" [ret msg :session-id (.sessionId pub) :dst-site site])))
  (offer-ready-reply! [this replica-version epoch]
    (let [ready-reply (->ReadyReply replica-version peer-id dst-peer-id session-id)
          payload ^bytes (messaging-compress ready-reply)
          buf ^UnsafeBuffer (UnsafeBuffer. payload)
          ret (.offer ^Publication pub buf 0 (.capacity buf))] 
      (info "Offer ready reply!:" [ret ready-reply :session-id (.sessionId pub) :dst-site site])))
  (offer-barrier-aligned! [this replica-version epoch]
    (let [barrier-aligned (->BarrierAlignedDownstream replica-version epoch peer-id dst-peer-id session-id)
          payload ^bytes (messaging-compress barrier-aligned)
          buf ^UnsafeBuffer (UnsafeBuffer. payload)
          ret (.offer ^Publication pub buf 0 (.capacity buf))]
      (info "Offered barrier aligned message message:" [ret barrier-aligned :session-id (.sessionId pub) :dst-site site])
      ret)))

(defn new-status-publisher [peer-config peer-id src-peer-id site]
  (->StatusPublisher peer-config peer-id src-peer-id site nil nil false false nil nil))

(deftype Subscriber 
  [peer-id ticket-counters peer-config dst-task-id slot-id site
   liveness-timeout channel ^Aeron conn ^Subscription subscription 
   ^:unsynchronized-mutable sources
   ^:unsynchronized-mutable status-pubs
   ^:unsynchronized-mutable ^ControlledFragmentAssembler assembler 
   ^:unsynchronized-mutable replica-version 
   ^:unsynchronized-mutable epoch
   ^:unsynchronized-mutable recover 
   ;; Going to need to be ticket per source, session-id per source, etc
   ;^:unsynchronized-mutable ^AtomicLong ticket 
   ^:unsynchronized-mutable batch]
  sub/Subscriber
  (start [this]
    (let [error-handler (reify ErrorHandler
                          (onError [this x] 
                            (println "Aeron messaging subscriber error" x)
                            ;(System/exit 1)
                            ;; FIXME: Reboot peer
                            (taoensso.timbre/warn x "Aeron messaging subscriber error")))
          media-driver-dir (:onyx.messaging.aeron/media-driver-dir peer-config)
          sinfo [dst-task-id slot-id :sources sources :to site]
          ctx (cond-> (Aeron$Context.)
                error-handler (.errorHandler error-handler)
                media-driver-dir (.aeronDirectoryName ^String media-driver-dir)
                true (.availableImageHandler (available-image sinfo))
                true (.unavailableImageHandler (unavailable-image sinfo)))
          conn (Aeron/connect ctx)
          liveness-timeout (arg-or-default :onyx.peer/publisher-liveness-timeout-ms peer-config)
          channel (autil/channel peer-config)
          stream-id (stream-id dst-task-id slot-id site)
          sub (.addSubscription conn channel stream-id)]
      (sub/add-assembler 
       (Subscriber. peer-id ticket-counters peer-config dst-task-id
                    slot-id site liveness-timeout channel conn sub sources
                    {} nil nil nil nil nil)))) 
  (stop [this]
    (info "Stopping subscriber" [dst-task-id slot-id site])
    (when subscription (.close subscription))
    (when conn (.close conn))
    (run! status-pub/stop (vals status-pubs))
    (Subscriber. peer-id ticket-counters peer-config dst-task-id slot-id site nil 
                 nil nil nil nil nil nil nil nil nil nil)) 
  (add-assembler [this]
    (set! assembler (ControlledFragmentAssembler. this))
    this)
  (info [this]
    {:subscription {:rv replica-version
                    :e epoch
                    :sources sources 
                    :dst-task-id dst-task-id 
                    :slot-id slot-id 
                    :blocked? (sub/blocked? this)
                    :site site
                    :channel (autil/channel peer-config)
                    :channel-id (.channel subscription)
                    :registation-id (.registrationId subscription)
                    :stream-id (.streamId subscription)
                    :closed? (.isClosed subscription)
                    :images (mapv autil/image->map (.images subscription))}
     :status-pubs (into {} (map (fn [[k v]] [k (status-pub/info v)]) status-pubs))})
  ;; TODO: add send heartbeat
  ;; This should be a separate call, only done once per task lifecycle, and also check when blocked
  (alive? [this]
    true
    #_(and (not (.isClosed subscription))
         (> (+ (sub/get-heartbeat this) liveness-timeout)
            (System/currentTimeMillis))))
  ;; TODO, implement peer transitionining on subscriber. RECONCILE
  (equiv-meta [this sub-info]
    (and (= dst-task-id (:dst-task-id sub-info))
         (= slot-id (:slot-id sub-info))
         (= site (:site sub-info))))
  (set-epoch! [this new-epoch]
    (set! epoch new-epoch)
    this)
  (set-replica-version! [this new-replica-version]
    (run! status-pub/new-replica-version! (vals status-pubs))
    (set! replica-version new-replica-version)
    (set! recover nil)
    this)
  (get-recover [this]
    recover)
  (set-recover! [this recover*]
    (assert recover*)
    (when-not (or (nil? recover) (= recover* recover)) 
      (throw (ex-info "Two different subscribers sent differing recovery information"
                      {:recover1 recover
                       :recover2 recover*
                       :replica-version replica-version
                       :epoch epoch})))
    (set! recover recover*)
    this)
  (prepare-poll! [this]
    (set! batch (transient []))
    this)
  (unblock! [this]
    (run! status-pub/unblock! (vals status-pubs))
    this)
  (blocked? [this]
    (not (some (complement status-pub/blocked?) (vals status-pubs))))
  (completed? [this]
    (not (some (complement status-pub/completed?) (vals status-pubs))))
  (poll-messages! [this]
    (info "Poll messages on channel" (autil/channel peer-config) "blocked" (sub/blocked? this))
    ;; TODO: Still needs to read on this!!!!
    ;; Just like poll replica, but can't read actual messages, but shouldn't be receiving them anyway
    (assert assembler)
    (let [_ (sub/prepare-poll! this)
          _ (info "Before poll" (sub/info this))
          rcv (.controlledPoll ^Subscription subscription ^ControlledFragmentHandler assembler fragment-limit-receiver)]
      (info "After poll" (sub/info this))
      (persistent! batch)))
  (offer-heartbeat! [this]
    (run! #(status-pub/offer-heartbeat! % replica-version epoch) (vals status-pubs)))
  (offer-barrier-aligned! [this peer-id]
    (let [status-pub (get status-pubs peer-id)] 
      (status-pub/offer-barrier-aligned! status-pub replica-version epoch)))
  (src-peers [this]
    (keys status-pubs))
  (update-sources! [this sources*]
    (let [prev-peer-ids (set (keys status-pubs))
          next-peer-ids (set (map :src-peer-id sources*))
          peer-id->site (into {} (map (juxt :src-peer-id :site) sources*))
          rm-peer-ids (clojure.set/difference prev-peer-ids next-peer-ids)
          add-peer-ids (clojure.set/difference next-peer-ids prev-peer-ids)
          removed (reduce (fn [spubs src-peer-id]
                            (status-pub/stop (get spubs src-peer-id))
                            (dissoc spubs src-peer-id))
                          status-pubs
                          rm-peer-ids)
          added (reduce (fn [spubs src-peer-id]
                          (assoc spubs 
                                 src-peer-id
                                 (status-pub/start 
                                  (new-status-publisher peer-config peer-id src-peer-id (get peer-id->site src-peer-id)))))
                        removed
                        add-peer-ids)]
      (set! status-pubs added)
      (set! sources sources*))
    this)
  (set-heartbeat! [this src-peer-id]
    (status-pub/set-heartbeat! (get status-pubs src-peer-id))
    this)
  (poll-replica! [this]
    (info "poll-replica!, sub-info:" (sub/info this))
    ;; TODO, should check heartbeats
    (sub/prepare-poll! this)
    (.controlledPoll ^Subscription subscription ^ControlledFragmentHandler assembler fragment-limit-receiver))
  ControlledFragmentHandler
  (onFragment [this buffer offset length header]
    (let [ba (byte-array length)
          _ (.getBytes ^UnsafeBuffer buffer offset ba)
          message (messaging-decompress ba)
          n-desired-messages 2
          ret (cond (< (:replica-version message) replica-version)
                    ControlledFragmentHandler$Action/CONTINUE

                    ;; Should this ever happen? Guess maybe if it's lagging?
                    ;; Leave in for now
                    (> (:replica-version message) replica-version)
                    ControlledFragmentHandler$Action/ABORT

                    (instance? onyx.types.Ready message)
                    (let [src-peer-id (:src-peer-id message)] 
                      (-> status-pubs
                          (get src-peer-id)
                          (status-pub/set-heartbeat!)
                          (status-pub/set-session-id! (.sessionId header))
                          (status-pub/offer-ready-reply! replica-version epoch))
                      
                      ControlledFragmentHandler$Action/CONTINUE)

                    ;; Can we skip over these even for the wrong replica version? 
                    ;; Probably since we would be sending a ready anyway
                    ;; This would prevent lagging peers blocking
                    (and (or (barrier? message)
                             (message? message))
                         (or (not= (:dst-task-id message) dst-task-id)
                             (not= (:slot-id message) slot-id)
                             ;; We're not caring about this src-peer-id
                             ;; I don't think this check is necessary
                             (not (get status-pubs (:src-peer-id message)))))
                    ControlledFragmentHandler$Action/CONTINUE

                    (heartbeat? message)
                    (do
                     (sub/set-heartbeat! this (:src-peer-id message))
                     ControlledFragmentHandler$Action/CONTINUE)

                    (barrier? message)
                    (if (zero? (count batch))
                      (let [src-peer-id (:src-peer-id message)
                            status-pub (get status-pubs src-peer-id)]
                       (assert-epoch-correct! epoch (:epoch message) message)
                       ;; For use determining whether job is complete. Refactor later
                       (sub/set-heartbeat! this src-peer-id)
                       (status-pub/block! status-pub)
                       (when (:completed? message) 
                         (status-pub/set-completed! status-pub (:completed? message)))
                       (some->> message :recover (sub/set-recover! this))
                       ControlledFragmentHandler$Action/BREAK)
                      ControlledFragmentHandler$Action/ABORT)

                    (message? message)
                    (if (>= (count batch) n-desired-messages) ;; full batch, get out
                      ControlledFragmentHandler$Action/ABORT
                      (let [_ (assert (pos? epoch))
                            session-id (.sessionId header)
                            src-peer-id (:src-peer-id message)
                            ticket (lookup-ticket ticket-counters src-peer-id session-id)
                            ticket-val ^long (.get ticket)
                            position (.position header)
                            assigned? (and (< ticket-val position)
                                           (.compareAndSet ticket ticket-val position))]
                        (when assigned?
                          (reduce conj! batch (:payload message)))
                        (sub/set-heartbeat! this (:src-peer-id message))
                        ControlledFragmentHandler$Action/CONTINUE))

                    :else
                    (throw (ex-info "Handler should never be here." {:replica-version replica-version
                                                                     :epoch epoch
                                                                     :message message})))]
      (info [:read-subscriber (action->kw ret) channel dst-task-id] (into {} message))
      ret)))

(defn new-subscription [peer-config peer-id ticket-counters sub-info]
  (let [{:keys [dst-task-id slot-id site]} sub-info]
    (->Subscriber peer-id ticket-counters peer-config dst-task-id slot-id site
                  nil nil nil nil nil nil nil nil nil nil nil)))
