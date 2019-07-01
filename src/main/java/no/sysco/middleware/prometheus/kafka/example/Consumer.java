package no.sysco.middleware.prometheus.kafka.example;

import io.prometheus.client.exporter.HTTPServer;
import no.sysco.middleware.prometheus.kafka.KafkaClientsJmxExports;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

public class Consumer {

    public static void main(String[] args) throws IOException {
        String id1 = UUID.randomUUID().toString();
        String group1 = UUID.randomUUID().toString();

        final String topic = "topic-in";

        final KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<String, String>(getConsumerProps(id1, group1));

//        DefaultExports.initialize();
        HTTPServer server = new HTTPServer(8081);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
//        KafkaClientsJmxExports.initialize(kafkaConsumer.metrics().keySet());

        kafkaConsumer.subscribe(Collections.singleton(topic));
        while (true) {
            ConsumerRecords<String, String> poll = kafkaConsumer.poll(Duration.ofMillis(100));
            for (ConsumerRecord record : poll) {
                System.out.println(record.key());
            }
        }
    }

    static Properties getConsumerProps(String id, String groupId) {
        final Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:29092");
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, id);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return properties;
    }
}
