(ns s3-beam.client
  (:import (goog Uri))
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [cljs-log.core :as log])
  (:require [cljs.reader :as reader]
            [cljs.core.async :as async :refer [chan put! close! pipeline-async]]
            [goog.dom :as gdom]
            [goog.net.XhrIo :as xhr]
            [cemerick.url :refer (url-encode)]))

(defn file->map [f]
  {:name (.-name f)
   :type (.-type f)
   :size (.-size f)})

(defn signing-url [server-url fname fmime]
  {:pre [(string? server-url) (string? fname) (string? fmime)]}
  (.toString (doto (Uri. server-url)
               (.setParameterValue "file-name" fname)
               (.setParameterValue "mime-type" fmime))))

(defn sign-file [server-url file ch]
  (let [fmap    (file->map file)
        edn-ize #(reader/read-string (.getResponseText (.-target %)))]
    (xhr/send (signing-url server-url (:name fmap) (:type fmap))
           (fn [res]
             (put! ch {:f file :signature (edn-ize res)})
             (close! ch)))))

(defn formdata-from-map [m]
  (let [fd (new js/FormData)]
    (doseq [[k v] m]
      (.append fd (name k) v))
    fd))

(defn presigned-url
  [signature]
  (let [query-params (dissoc signature :Action)
        query-string (clojure.string/join "&" (map (fn [[k v]] (str (name k) "=" (url-encode v))) query-params))]
    (str (:Action signature) "?" query-string)))

(defn upload-file [upload-info ch]
  (let [sig-fields [:Action :X-Amz-Algorithm :X-Amz-Date :X-Amz-SignedHeaders :X-Amz-Expires :X-Amz-Credential :X-Amz-Signature]
        signature  (select-keys (:signature upload-info) sig-fields)
        url (presigned-url signature)
        form-data (:f upload-info)]
    (xhr/send
     url
     (fn [res]
       (let [status (. (.-target res) (getStatus))]
         (if (= status 200)
           (put! ch :success)
           (put! ch :failure))
         (close! ch)))
     "PUT"
     form-data)))

(defn s3-pipe
  "Takes a channel where completed uploads will be reported and
  returns a channel where you can put File objects that should get uploaded.
  May also take an options map with:
    :server-url - the sign server url, defaults to \"/sign\""
  ([report-chan] (s3-pipe report-chan {:server-url "/sign"}))
  ([report-chan opts]
   (let [to-process (chan)
         signed     (chan)]
     (pipeline-async 3 signed (partial sign-file (:server-url opts)) to-process)
     (pipeline-async 3 report-chan upload-file signed)
     to-process)))
