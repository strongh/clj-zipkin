(ns clj-zipkin.tracer
  (:require [thrift-clj.core :as thrift]
            [clojure.data.codec.base64 :as b64]
            [clj-scribe :as scribe]
            [thrift-clj.gen.core :as c]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce]))

;(def bann (BinaryAnnotation. "name" (byte-streams/convert "abcd" java.nio.ByteBuffer) AnnotationType/STRING nil))

(def ^:dynamic *default-service-name* "Unknown Service")

(thrift/import
  (:types [com.twitter.zipkin.gen
           Span Annotation BinaryAnnotation AnnotationType Endpoint
           LogEntry StoreAggregatesException AdjustableRateException])
  (:clients com.twitter.zipkin.gen.ZipkinCollector))

(defn ip-str-to-int [str-ip]
   (let [nums (vec (map read-string (clojure.string/split str-ip #"\.")))]
      (+
         (* (nums 0) 16777216)
         (* (nums 1) 65536)
         (* (nums 2) 256)
         (nums 3))))

(defn str->bytes
  [str]
  (bytes (byte-array (map (comp byte int) str))))

(defn thrift->base64
  "Serializes a thrift span object to be sent through the wires"
  [data]
  (let [buffer (org.apache.thrift.transport.TMemoryBuffer. 40000)
        protocol (org.apache.thrift.protocol.TBinaryProtocol. buffer)
        ;side effectish step
        _ (.write (.to_thrift* data) protocol)]
    (String. (b64/encode (.getArray buffer) 0 (.length buffer)) "UTF-8")))

(defn host->endpoint
  "Given a host in string or map format creates a thrift zipkin endpoint object"
  [host]
  (condp = (class host)
    java.lang.String (Endpoint. (ip-str-to-int host) 0 *default-service-name*)
    clojure.lang.PersistentArrayMap (Endpoint. (ip-str-to-int (or (:ip host)
                                                                  (.getHostAddress (java.net.InetAddress/getLocalHost))))
                                               (or (:port host) 0)
                                               (or (:service host) *default-service-name*))
    nil (Endpoint. (ip-str-to-int (.getHostAddress (java.net.InetAddress/getLocalHost)))
                   0
                   *default-service-name*)
    (throw (str "Invalid host value" (class host) "not supported"))))

;;According to tryfer sources, zipkin has trouble recording traces with ids
;;larger than (2 ** 56) - 1
(def rand-max (dec (Math/pow 2 24)))

(defn create-id
  "Creates a new id to be used as trace or span id"
  []
  (rand-int rand-max))

(defn create-timestamp-span
  "Creates a new span with start/finish annotations"
  [span host trace-id span-id parent-id start finish]
  (let [endpoint (host->endpoint host)
        start-annotation (Annotation. (* 1000 (time-coerce/to-long start))
                                      (str "start:" (name span)) endpoint 0)
        finish-annotation (Annotation. (* 1000 (time-coerce/to-long finish))
                                       (str "end:" (name span)) endpoint 0)]
    (thrift->base64
     (Span. trace-id
            (name span)
            span-id
            parent-id
            [start-annotation
             finish-annotation]
            []
            0))))

;;tracing macro for nested recording

(defmulti parse-item (fn [form]
                       (cond
                        (seq? form) :seq
                        (vector? form) :vector
                        :else :default)))

;;if found a call to original trace in the ast
;;change for a call to trace* in order to use
;;only one logger at the top
(defmethod parse-item :seq
  [form]
  (if (and (symbol? (first form))
           (= (ns-resolve *ns* (first form))
              (ns-resolve 'clj-zipkin.tracer 'trace)))
    (let [[_ data body] form]
      (list* 'clj-zipkin.tracer/trace*
             data
             (doall (map parse-item body))
             '()))
    (doall (map parse-item form))))

(defmethod parse-item :vector
  [v]
  (vec (doall (map parse-item v))))

(defmethod parse-item :default
  [item]
  item)

(defmacro trace*
  "Creates a start/finish timestamp annotations span
   for the code chunk received, defers actual logging to upper trace function."
  [{:keys [span host span-id]} & body]
  (let [body (parse-item body)]
    `(let [parent-id# ~'span-id
           ~'span-id (or ~span-id (create-id))
          ; _# (println "t" ~'trace-id "s" ~'span-id "p" parent-id#)
           start-time# (time/now)
           result# ~@body
           end-time# (time/now)
           span# (create-timestamp-span ~span ~host ~'trace-id
                                        ~'span-id parent-id#
                                        start-time# end-time#)
           ;parent spans added at the end, so cons
           _# (swap! ~'span-list (fn [l#] (cons span# l#)))]
       result#)))

(defmacro trace
  "Timestamp tracing of a code chunk using timestamp annotations.
  => (trace options body)

  options {
  :host => current host, defaults to InetAddress/getLocalHost if unspecifyed
  :span => span name
  :trace-id => optional, new one will be created if unspecifyed
  :scribe => scribe/zipkin endpoint configuration {:host h :port p}
  }

  Macro uses anaphoras for nested tracing so the following variable names are defined:
  * trace-id
  * span-id
  * span-list

  (trace {:host \"10.0.2.1\" :span \"GET\" :scribe {host \"localhost\" :port 9410}}
    (...code to be traced...))

  Trace Id can be specified with the :trace-id option

  (trace {:host \"10.0.2.1\" :trace-id 12345 :span \"GET\" :scribe {host \"localhost\" :port 9410}}
    (...code to be traced...))

  Nested traces are supported and scribe configuration is not needed for inner traces,
  those will be treated as child spans of the immediate parent trace

  (trace {:host \"10.0.2.1\" :span \"GET\" :scribe {host \"localhost\" :port 9410}}
     (...code to be traced...
       (trace {:span \"OTHER\"}
         (..code...))))"
  [& args]
  `(let [logger# (scribe/async-logger :host (-> ~(-> args first :scribe) :host)
                                      :port (-> ~(-> args first :scribe) :port)
                                      :category "zipkin")
         ~'trace-id (or ~(-> args first :trace-id)
                        (create-id))
         ~'span-list (atom [])
         ~'span-id ~(-> args first :parent-span-id)
         result# (trace* ~(first args) ~@(rest args))
         _# (scribe/log logger# (deref ~'span-list))]
     result#))
