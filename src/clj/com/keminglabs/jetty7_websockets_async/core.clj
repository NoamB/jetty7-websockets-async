(ns com.keminglabs.jetty7-websockets-async.core
  (:require [clojure.core.async :refer [go chan close! <!! <! >!! >! dropping-buffer]]
            [ring.util.servlet :as servlet])
  (:import (org.eclipse.jetty.websocket WebSocket WebSocket$OnTextMessage WebSocketHandler WebSocketClientFactory)
           org.eclipse.jetty.server.handler.ContextHandler
           org.eclipse.jetty.server.handler.ContextHandlerCollection
           java.net.URI))

(defn default-chan []
  (chan (dropping-buffer 137)))

(defn ->WebSocket$OnTextMessage
  [connection-chan {:keys [in out] :as connection-msg}]
  (proxy [WebSocket$OnTextMessage] []
    (onOpen [conn]
      (>!! connection-chan (assoc connection-msg :conn conn))
      (go (loop []
            (let [^String msg (<! in)]
              (if (nil? msg)
                (do
                  (close! out)
                  (.close conn))
                (do
                  (.sendMessage conn msg)
                  (recur)))))))
    (onMessage [msg]
      (>!! out msg))
    (onClose [close-code msg]
      (close! in)
      (close! out))))


;;;;;;;;;;;;;;;;;;
;;WebSocket client

(def ws-client-factory
  (WebSocketClientFactory.))

;;TODO: how to prevent docstring duplication?
(defn connect!
  "Tries to connect to websocket at `url`; if sucessful, places a request map on `connection-chan`.
Request maps contain following keys:

  :uri  - the string URI on which the connection was made
  :conn - the underlying Jetty7 websocket connection (see: http://download.eclipse.org/jetty/stable-7/apidocs/org/eclipse/jetty/websocket/WebSocket.Connection.html)
  :in   - a core.async port where you can put string messages
  :out  - a core.async port whence string messages

Accepts the following options:

  :in   - a zero-arg function called to create the :in port for each new websocket connection (default: a non-blocking dropping channel)
  :out  - a zero-arg function called to create the :out port for each new websocket connection (default: a non-blocking dropping channel)
"
  ([connection-chan url]
     (connect! connection-chan url {}))
  ([connection-chan url {:keys [in out]
                         :or {in default-chan, out default-chan}}]

     ;;Start WebSocket client factory on first `connect!` call.
     (when-not (or (.isStarted ws-client-factory)
                   (.isStarting ws-client-factory))
       (.start ws-client-factory))


     (.open (.newWebSocketClient ws-client-factory)
            (URI. url)
            (->WebSocket$OnTextMessage connection-chan
                                       {:uri url :in (in) :out (out)}))))


;;;;;;;;;;;;;;;;;;
;;WebSocket server

(defn handler
  [connection-chan in-thunk out-thunk auth-handler]
  (proxy [WebSocketHandler] []
    (doWebSocketConnect [request response]
      (let [ring-request (servlet/build-request-map request)
            ring-response (if auth-handler
                            (auth-handler request)
                            {:status 200})
            status (:status ring-response)
            session (:session ring-response)]
        (when (= status 200)
          (let [in (in-thunk)
                out (out-thunk)
                decorated-ring-request (assoc ring-request :session session)]
            (->WebSocket$OnTextMessage connection-chan
                                       {:request decorated-ring-request
                                        :in      in
                                        :out     out})))))))

(defn configurator
  "Returns a Jetty configurator that configures server to listen for websocket connections and put request maps on `connection-chan`.

Request maps contain following keys:

  :request - basic ring request map
  :conn    - the underlying Jetty7 websocket connection (see: http://download.eclipse.org/jetty/stable-7/apidocs/org/eclipse/jetty/websocket/WebSocket.Connection.html)
  :in      - a core.async port where you can put string messages
  :out     - a core.async port whence string messages

Accepts the following options:

  :path - the string path at which the server should listen for websocket connections (default: \"/\")
  :in   - a zero-arg function called to create the :in port for each new websocket connection (default: a non-blocking dropping channel)
  :out  - a zero-arg function called to create the :out port for each new websocket connection (default: a non-blocking dropping channel)
  :auth-handler - a ring handler that will be run with the request, rejecting the user on a non-200 response (default: nil)
"
  ([connection-chan]
     (configurator connection-chan {}))
  ([connection-chan {:keys [in out path auth-handler]
                     :or   {in default-chan, out default-chan, path "/" auth-handler nil}}]
     (fn [server]
       (let [ws-handler (handler connection-chan in out auth-handler)
             existing-handler (.getHandler server)
             contexts (doto (ContextHandlerCollection.)
                        (.setHandlers (into-array [(doto (ContextHandler. path)
                                                     (.setAllowNullPathInfo true)
                                                     (.setHandler ws-handler))

                                                   (doto (ContextHandler. "/")
                                                     (.setHandler existing-handler))])))]
         (.setHandler server contexts)))))
