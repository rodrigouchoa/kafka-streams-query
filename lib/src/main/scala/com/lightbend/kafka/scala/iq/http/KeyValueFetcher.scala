/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.kafka.scala.iq
package http

import akka.actor.ActorSystem

import org.apache.kafka.streams.{ KafkaStreams }
import org.apache.kafka.streams.state.HostInfo
import org.apache.kafka.common.serialization.Serializer

import scala.concurrent.{ Future, ExecutionContext}
import scala.util.{ Success, Failure }

import com.typesafe.scalalogging.LazyLogging
import services.{ MetadataService, HostStoreInfo, LocalStateStoreQuery }
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import akka.http.scaladsl.model.ResponseEntity
import akka.http.scaladsl.unmarshalling.Unmarshaller
import serializers.Serializers
import io.circe.Decoder

/**
 * Abstraction for fetching information from a key/value state store based on the
 * key and the store name passed in the API.
 *
 * Supports basic fetch as well as fetch over a time window.
 *
 * The fetch APIs support retry semantics in case the key is not available in the local state store. It
 * then fetches the store information from the MetadataService and then requeries that store
 * to get the information.
 */ 
class KeyValueFetcher[K: Decoder, V: Decoder](
  metadataService: MetadataService, 
  localStateStoreQuery: LocalStateStoreQuery[K, V],
  httpRequester: HttpRequester, 
  streams: KafkaStreams, 
  executionContext: ExecutionContext, 
  hostInfo: HostInfo)(implicit actorSystem: ActorSystem, keySerializer: Serializer[K], u: Unmarshaller[ResponseEntity, V])

  extends LazyLogging 
  with FailFastCirceSupport with Serializers {

  private implicit val ec: ExecutionContext = executionContext

  /**
   * Query for a key
   */ 
  def fetch(key: K, store: String, path: String): Future[V] = { 

    metadataService.streamsMetadataForStoreAndKey(store, key, keySerializer) match {
      case Success(host) => {
        // key is on another instance. call the other instance to fetch the data.
        if (!thisHost(host)) {
          logger.warn(s"Key $key is on another instance not on $host - requerying ..")
          httpRequester.queryFromHost[V](host, path)
        } else {
          // key is on this instance
          localStateStoreQuery.queryStateStore(streams, store, key)
        }
      }
      case Failure(ex) => Future.failed(ex)
    }
  }

  /**
   * Query all
   */ 
  def fetchAll(store: String, path: String): Future[List[(K, V)]] = { 

    def fetchKVs(host: HostStoreInfo): Future[List[(K, V)]] = {
      if (!thisHost(host)) {
          
        // host is remote - need to requery
        httpRequester.queryFromHost[List[(K, V)]](host, path)
      } else {

        // fetch all kvs for this local store
        localStateStoreQuery.queryStateStoreForAll(streams, store)
      }
    }

    metadataService.streamsMetadataForStore(store) match {

      // metadata could not be found for this store
      case Nil => Future.failed(new Exception(s"No metadata found for $store"))

      // all hosts that have this store with the same application id
      case hosts => Future.traverse(hosts)(fetchKVs).map(_.flatten)
    }
  }

  /**
   * Query for a window
   */
  def fetchWindowed(key: K, store: String, path: String, 
    fromTime: Long, toTime: Long): Future[List[(Long, V)]] = 

    metadataService.streamsMetadataForStoreAndKey(store, key, keySerializer) match {
      case Success(host) => {
        // key is on another instance. call the other instance to fetch the data.
        if (!thisHost(host)) {
          logger.warn(s"Key $key is on another instance not on $host - requerying ..")
          httpRequester.queryFromHost[List[(Long, V)]](host, path)
        } else {
          // key is on this instance
          localStateStoreQuery.queryWindowedStateStore(streams, store, key, fromTime, toTime)
        }
      }
      case Failure(ex) => Future.failed(ex)
    }

  private def thisHost(host: HostStoreInfo): Boolean = 
    host.host.equals(translateHostInterface(hostInfo.host)) && host.port == hostInfo.port
}

