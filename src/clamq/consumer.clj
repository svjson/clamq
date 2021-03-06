(ns clamq.consumer
 (:import
   [javax.jms BytesMessage ObjectMessage TextMessage ExceptionListener MessageListener]
   [org.springframework.jms.listener DefaultMessageListenerContainer]
   )
 (:use
   [clamq.helpers]
   )
 )

(defprotocol Consumer
  (start [self])
  (stop [self])
  )

(defn- convert-message [message]
  (cond
    (instance? TextMessage message)
    (.getText message)
    (instance? ObjectMessage message)
    (.getObject message)
    (instance? BytesMessage message)
    (let [byteArray (byte-array (.getBodyLength message))] (.readBytes message byteArray) byteArray)
    :else
    (throw (IllegalStateException. (str "Unknown message format: " (class message))))
    )
  )

(defn- proxy-message-listener [handler-fn failure-fn limit container]
  (let [counter (atom 0)]
    (proxy [MessageListener] []
      (onMessage [message]
        (swap! counter inc)
        (let [converted (convert-message message)]
          (try
            (handler-fn converted)
            (catch Exception ex (failure-fn {:message converted :exception ex}))
            (finally (if (= limit @counter) (do (.stop container) (future (.shutdown container)))))
            )
          )
        )
      )
    )
  )

(defn consumer [connection destination handler-fn & {transacted :transacted pubSub :pubSub limit :limit failure-fn :on-failure :or {pubSub false limit 0 failure-fn rethrow-on-failure}}]
  (if (nil? connection) (throw (IllegalArgumentException. "No value specified for connection!")))
  (if (nil? destination) (throw (IllegalArgumentException. "No value specified for destination!")))
  (if (nil? transacted) (throw (IllegalArgumentException. "No value specified for transacted!")))
  (if (nil? handler-fn) (throw (IllegalArgumentException. "No value specified for handler function!")))
  (let [container (DefaultMessageListenerContainer.) listener (proxy-message-listener handler-fn failure-fn limit container)]
    (doto container
      (.setConnectionFactory connection)
      (.setDestinationName destination)
      (.setMessageListener listener)
      (.setSessionTransacted transacted)
      (.setPubSubDomain pubSub)
      )
    (reify Consumer
      (start [self] (do (doto container (.start) (.initialize)) nil))
      (stop [self] (do (.shutdown container) nil))
      )
    )
  )


