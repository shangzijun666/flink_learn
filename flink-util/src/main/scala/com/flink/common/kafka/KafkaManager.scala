package com.flink.common.kafka

import java.util.Properties

import org.apache.flink.streaming.connectors.kafka.{FlinkKafkaConsumer, KafkaDeserializationSchema}

import scala.collection.JavaConversions._

object KafkaManager {
  /**
   *
   */
  def getKafkaParam(broker: String, groupID: String = "test") = {
    val pro = new Properties()
    pro.put("bootstrap.servers", broker)
    // pro.put("zookeeper.connect", KAFKA_ZOOKEEPER)
    pro.put("group.id", groupID)
    pro.put("auto.commit.enable", "false") //kafka 0.8-
    pro.put("enable.auto.commit", "false") //kafka 0.9+
    // pro.put("auto.commit.interval.ms", "60000")
    pro
  }
  case class KafkaMessge(topic: String,
                         offset: Long,
                         ts: Long,
                         msg: String,
                         rowtime: String,
                         uid: String)

  /**
   *
   * @param topic
   * @param broker
   * @return
   */
  def getKafkaSource[T](topic: String,
                        broker: String,
                        deserialize: KafkaDeserializationSchema[T]): FlinkKafkaConsumer[T] = {
    new FlinkKafkaConsumer[T](topic.split(",").toList,
      deserialize,
      getKafkaParam(broker))
  }


  /**
   *
   * @param topic
   * @param broker
   * @return
   */
//  def kafkaSource(
//                   topic: String,
//                   broker: String): FlinkKafkaConsumer[KafkaMessge] = {
//    val kafkasource = KafkaManager.getKafkaSource(
//      topic,
//      broker,
//      new TopicOffsetMsgDeserialize())
//    kafkasource.setCommitOffsetsOnCheckpoints(true)
//    kafkasource.setStartFromLatest() //不加这个默认是从上次消费
//    kafkasource
//  }
}
