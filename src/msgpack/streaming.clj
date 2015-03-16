(ns msgpack.streaming
  (:require [msgpack.io :refer :all])
  (:import java.io.DataOutputStream
           java.io.ByteArrayOutputStream
           java.nio.charset.Charset))

(defprotocol Packable
  "Objects that can be serialized as MessagePack types"
  (pack-stream [this output-stream]))

(defmacro cond-let [bindings & clauses]
  `(let ~bindings (cond ~@clauses)))

(declare pack-number pack-bytes)

(extend-protocol Packable
  nil
  (pack-stream
    [_ s]
    (.writeByte s 0xc0))

  Boolean
  (pack-stream
    [bool s]
    (if bool
      (.writeByte s 0xc3)
      (.writeByte s 0xc2)))

  Byte
  (pack-stream
    [n s]
    (pack-number n s))

  Short
  (pack-stream
    [n s]
    (pack-number n s))

  Integer
  (pack-stream
    [n s]
    (pack-number n s))

  Long
  (pack-stream
    [n s]
    (pack-number n s))

  clojure.lang.BigInt
  (pack-stream
    [n s]
    (pack-number n s))

  ;; TODO floating point numbers should be size-optimized as above

  Float
  (pack-stream
    [f s]
    (do (.writeByte s 0xca) (.writeFloat s f)))

  Double
  (pack-stream
    [d s]
    (do (.writeByte s 0xcb) (.writeDouble s d)))

  clojure.lang.Ratio
  (pack-stream
    [r s]
    (pack-stream (double r) s))

  String
  (pack-stream
    [str s]
    (cond-let [bytes (.getBytes str (Charset/forName "UTF-8"))
               len (count bytes)]
              (<= len 0x1f)
              (do (.writeByte s (bit-or 2r10100000 len)) (.write s bytes))

              (<= len 0xff)
              (do (.writeByte s 0xd9) (.writeByte s len) (.write s bytes))

              (<= len 0xffff)
              (do (.writeByte s 0xda) (.writeShort s len) (.write s bytes))

              (<= len 0xffffffff)
              (do (.writeByte s 0xdb) (.writeInt s len) (.write s bytes)))))

; Note: the extensions below are not in extend-protocol above because of
; a Clojure bug. See http://dev.clojure.org/jira/browse/CLJ-1381

; Array of java.lang.Byte (boxed)
(extend-type (class (java.lang.reflect.Array/newInstance Byte 0))
  Packable
  (pack-stream [bytes s] (pack-bytes bytes s)))

; Array of primitive bytes (un-boxed)
(extend-type (Class/forName "[B")
  Packable
  (pack-stream [bytes s] (pack-bytes bytes s)))

(defn- pack-bytes
  [bytes s]
  (cond-let [len (count bytes)]
            (<= len 0xff)
            (do (.writeByte s 0xc4) (.writeByte s len) (.write s bytes))

            (<= len 0xffff)
            (do (.writeByte s 0xc5) (.writeShort s len) (.write s bytes))

            (<= len 0xffffffff)
            (do (.writeByte s 0xc6) (.writeInt s len) (.write s bytes))))

(defn- pack-number
  "Pack n using the most compact representation possible"
  [n s]
  (cond
    ; +fixnum
    (<= 0 n 127)                  (.writeByte s n)
    ; -fixnum
    (<= -32 n -1)                 (.writeByte s n)
    ; uint 8
    (<= 0 n 0xff)                 (do (.writeByte s 0xcc) (.writeByte s n))
    ; uint 16
    (<= 0 n 0xffff)               (do (.writeByte s 0xcd) (.writeShort s n))
    ; uint 32
    (<= 0 n 0xffffffff)           (do (.writeByte s 0xce) (.writeInt s n))
    ; uint 64
    (<= 0 n 0xffffffffffffffff)   (do (.writeByte s 0xcf) (.writeLong s n))
    ; int 8
    (<= -0x80 n -1)               (do (.writeByte s 0xd0) (.writeByte s n))
    ; int 16
    (<= -0x8000 n -1)             (do (.writeByte s 0xd1) (.writeShort s n))
    ; int 32
    (<= -0x80000000 n -1)         (do (.writeByte s 0xd2) (.writeInt s n))
    ; int 64
    (<= -0x8000000000000000 n -1) (do (.writeByte s 0xd3) (.writeLong s n))))

(defn pack [obj]
  (let [baos (ByteArrayOutputStream.)
        dos (DataOutputStream. baos)]
    (do
      (pack-stream obj dos)
      (seq (.toByteArray baos)))))
