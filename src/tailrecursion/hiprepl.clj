(ns tailrecursion.hiprepl
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojail.core    :refer [sandbox safe-read]]
            [clojail.testers :refer [secure-tester]])
  (:import
   [java.util.concurrent ExecutionException]
   [java.io StringWriter]
   [org.jivesoftware.smack ConnectionConfiguration XMPPConnection XMPPException PacketListener]
   [org.jivesoftware.smack.packet Message Presence Presence$Type]
   [org.jivesoftware.smackx.muc MultiUserChat])
  (:gen-class))

(defn packet-listener [conn processor]
  (reify PacketListener
    (processPacket [_ packet]
      (processor conn packet))))

(defn message->map [#^Message m]
  (try
    {:body (.getBody m)
     :from (.getFrom m)}
    (catch Exception e (println e) {})))

(defn with-message-map [handler]
  (fn [muc packet]
    (let [message (message->map #^Message packet)]
      (try
       (handler muc message)
       (catch Exception e (println e))))))

(defn wrap-responder [handler]
  (fn [muc message]
    (if-let [resp (handler message)]
      (.sendMessage muc resp))))

(defn connect
  [username password resource]
  (let [conn (XMPPConnection. (ConnectionConfiguration. "chat.hipchat.com" 5222))]
    (.connect conn)
    (try
      (.login conn username password resource)
      (catch XMPPException e
        (throw (Exception. "Couldn't log in with user's credentials."))))
    (.sendPacket conn (Presence. Presence$Type/available))
    conn))

(defn join
  [conn room room-nickname handler]
  (let [muc (MultiUserChat. conn (str room "@conf.hipchat.com"))]
    (.join muc room-nickname)
    (.addMessageListener muc
                         (packet-listener muc (with-message-map (wrap-responder handler))))
    muc))

#_(let [history (atom [nil nil nil])
      last-exception (atom nil)]
  (try
    (let [bindings {#'*print-length* 30
                    #'*1 (nth @history 0)
                    #'*2 (nth @history 1)
                    #'*3 (nth @history 2)
                    #'*e @last-exception}
          result (/ 1 0)]
      (swap! history (constantly [result (nth @history 0) (nth @history 1)]))
      (pr result))
    (catch Throwable t
      (swap! last-exception (constantly t))
      (print (.getMessage t)))))

(defn make-safe-eval
  [{sandbox-config :sandbox}]
  (let [our-sandbox (sandbox @(find-var sandbox-config))
        history (atom [nil nil nil])
        last-exception (atom nil)]
    (fn [form output]
      (binding [*out* output]
        (try
          (let [bindings {#'*print-length* 30
                          #'*1 (nth @history 0)
                          #'*2 (nth @history 1)
                          #'*3 (nth @history 2)
                          #'*e @last-exception
                          #'*out* output
                          #'*err* output}
                result (our-sandbox form bindings)]
            (swap! history (constantly [result (nth @history 0) (nth @history 1)]))
            (pr result))
          (catch ExecutionException e
            (swap! last-exception (constantly (.getCause e)))
            (print (.getMessage e)))
          (catch Throwable t
            (swap! last-exception (constantly t))
            (print (.getMessage t))))))))

(defn dispatches
  [config room]
  (let [safe-eval (make-safe-eval config)]
    [[#",.*"
      (fn [body from]
        (let [output (StringWriter.)]
          (safe-eval (safe-read (.substring body 1)) output)
          (.toString output)))] ]))

(defn message-handler
  [{nickname :room-nickname :as config} room]
  (let [the-dispatches (dispatches config room)]
    (fn [{:keys [body from]}]
      (when (not= nickname
                  (string/replace (or from "") #"^[^/]*/" ""))
        (reduce (fn [_ [re func]]
                  (when (re-matches re body)
                    (if-let [resp (func body from)]
                      (reduced resp))))
                nil
                the-dispatches)))))

(defn -main
  []
  (let [{:keys [username password rooms room-nickname] :as config} (safe-read (slurp (io/resource "config.clj")))
        conn (connect username password "bot")]
    (doseq [room rooms]
      (join conn room room-nickname (message-handler config room)))
    @(promise)))
