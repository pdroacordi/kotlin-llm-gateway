package io.github.pdroacordi.llmgateway.testsupport

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

class SharedRedpandaTest {
    private val bootstrap =
        mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to SharedRedpanda.bootstrapServers)

    @Test
    fun `the cluster has one broker`() {
        AdminClient.create(bootstrap).use { admin ->
            val nodes = admin.describeCluster().nodes().get(30, TimeUnit.SECONDS)
            assertEquals(1, nodes.size, nodes.toString())
        }
    }

    @Test
    fun `a record produced to a new topic is consumed back`() {
        val topic = "shared-redpanda-${UUID.randomUUID()}"
        AdminClient.create(bootstrap).use { admin ->
            admin.createTopics(listOf(NewTopic(topic, 1, 1.toShort()))).all().get(30, TimeUnit.SECONDS)
        }

        KafkaProducer<String, String>(
            bootstrap + mapOf(ProducerConfig.ACKS_CONFIG to "all"),
            StringSerializer(),
            StringSerializer(),
        ).use { producer ->
            producer.send(ProducerRecord(topic, "key-1", "hello")).get(30, TimeUnit.SECONDS)
        }

        val consumerConfig =
            bootstrap +
                mapOf(
                    ConsumerConfig.GROUP_ID_CONFIG to "group-$topic",
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                )
        KafkaConsumer(consumerConfig, StringDeserializer(), StringDeserializer()).use { consumer ->
            consumer.subscribe(listOf(topic))
            val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            val values = mutableListOf<String>()
            while (values.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach { values += it.value() }
            }
            assertEquals(listOf("hello"), values)
        }
    }
}
