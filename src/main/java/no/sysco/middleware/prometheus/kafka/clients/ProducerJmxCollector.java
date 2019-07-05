package no.sysco.middleware.prometheus.kafka.clients;


import io.prometheus.client.Collector;
import io.prometheus.client.GaugeMetricFamily;
import no.sysco.middleware.prometheus.kafka.KafkaClientJmxCollector;
import no.sysco.middleware.prometheus.kafka.template.ProducerMetricTemplates;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.streams.KeyValue;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Kafka-Producer register 4 types of jmx metrics in one `kafka.producer` domain.
 * ref: https://kafka.apache.org/documentation/#producer_monitoring
 * <p>
 * 1. `app-info` - metric group name is going to be deprecated.
 * This metric group does not supported by current implementation.
 * 2. `producer-metrics` - common producer sender metrics.
 * kafka.producer:type=producer-metrics,client-id="{client-id}"
 * 3. `producer-node-metric` - metrics is per broker. Common metric for all clients.
 * kafka.[producer|consumer|connect]:type=[consumer|producer|connect]-node-metrics,client-id=([-.\w]+),node-id=([0-9]+)
 * 4. `producer-topic-metrics` - metrics per topic.
 * kafka.producer:type=producer-topic-metrics,client-id="{client-id}",topic="{topic}"
 */
public class ProducerJmxCollector extends KafkaClientJmxCollector {

    private final Set<MetricName> producerMetricNames;
    private ProducerMetricTemplates producerMetricTemplates;

    private ProducerJmxCollector(MBeanServer mBeanServer, String domainName) {
        super(mBeanServer, domainName);
        this.producerMetricTemplates = new ProducerMetricTemplates();
        Set<String> kafkaClientIds = getKafkaClientIds(ProducerMetricTemplates.PRODUCER_METRIC_GROUP_NAME);
        Set<MetricName> metricNamesCommon = producerMetricTemplates.getMetricNamesCommon(kafkaClientIds);
        Set<MetricName> metricNamesProducerInstance = producerMetricTemplates.getMetricNamesProducerInstance(kafkaClientIds);
        // this metric names initialised at start time.
        this.producerMetricNames = Stream.of(metricNamesCommon, metricNamesProducerInstance).flatMap(Set::stream).collect(Collectors.toSet());
    }

    public ProducerJmxCollector() {
        this(
                ManagementFactory.getPlatformMBeanServer(),
                ProducerMetricTemplates.PRODUCER_DOMAIN
        );
    }

    /**
     * Producer client could write to several topics.
     * Examples:
     * <p>
     * kafka.producer:type=producer-topic-metrics,client-id=A,topic=my-games-topic
     * kafka.producer:type=producer-topic-metrics,client-id=A,topic=transactions-topic
     * kafka.producer:type=producer-topic-metrics,client-id=A,topic=my-friends-topic
     */
    public Set<KeyValue<String, String>> getClientTopicSet(final String clientId) {
        String objectNameWithDomain =
                ProducerMetricTemplates.PRODUCER_DOMAIN +
                        ":type=" + ProducerMetricTemplates.PRODUCER_TOPIC_METRIC_GROUP_NAME +
                        ",client-id=" + clientId + ",*";
        Set<KeyValue<String, String>> clientTopicList = new HashSet<>();
        try {
            ObjectName mbeanObjectName = new ObjectName(objectNameWithDomain);
            Set<ObjectName> objectNamesFromString = mBeanServer.queryNames(mbeanObjectName, null);
            for (ObjectName objectName : objectNamesFromString) {
                String id = objectName.getKeyProperty("client-id");
                String topicName = objectName.getKeyProperty("topic");
                clientTopicList.add(KeyValue.pair(id, topicName));
            }
            return clientTopicList;
        } catch (MalformedObjectNameException mfe) {
            throw new IllegalArgumentException(mfe.getMessage());
        }
    }

    /**
     * Producer client could write to several topics.
     * Examples:
     * <p>
     * kafka.producer:type=producer-topic-metrics,client-id=A,node-id=node--1
     * kafka.producer:type=producer-topic-metrics,client-id=B,node-id=node--2
     * kafka.producer:type=producer-topic-metrics,client-id=A,node-id=node--2
     */
    public Set<KeyValue<String, String>> getClientNodeSet(final String clientId) {
        String objectNameWithDomain =
                ProducerMetricTemplates.PRODUCER_DOMAIN +
                        ":type=" + producerMetricTemplates.perBrokerTemplates.metricGroupName +
                        ",client-id=" + clientId + ",*";
        Set<KeyValue<String, String>> clientNodeList = new HashSet<>();
        try {
            ObjectName mbeanObjectName = new ObjectName(objectNameWithDomain);
            Set<ObjectName> objectNamesFromString = mBeanServer.queryNames(mbeanObjectName, null);
            for (ObjectName objectName : objectNamesFromString) {
                String id = objectName.getKeyProperty("client-id");
                String nodeId = objectName.getKeyProperty("node-id");
                clientNodeList.add(KeyValue.pair(id, nodeId));
            }
            return clientNodeList;
        } catch (MalformedObjectNameException mfe) {
            throw new IllegalArgumentException(mfe.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    // producer-metrics
    public List<Collector.MetricFamilySamples> getMetricsPerClientIdTopic(final String metricType, final Set<MetricName> metricNames) {
        List<Collector.MetricFamilySamples> metricFamilySamples = new ArrayList<>();
        for (MetricName metricName : metricNames) {
            String clientId = metricName.tags().get("client-id");
            String topic = metricName.tags().get("topic");
            GaugeMetricFamily gaugeMetricFamily = new GaugeMetricFamily(
                    formatMetricName(metricName),
                    metricName.description(),
                    Arrays.asList("client-id", "topic")
            );
            gaugeMetricFamily.addMetric(
                    Arrays.asList(clientId, topic),
                    getMBeanAttributeValue(metricType, metricName.name(), KeyValue.pair("client-id", clientId), KeyValue.pair("topic", topic))
            );
            metricFamilySamples.add(gaugeMetricFamily);
        }
        return metricFamilySamples;
    }


    List<Collector.MetricFamilySamples> getMetricsProducerTopic() {
        // producer-topic-metrics
        String perTopicMetricGroupName = producerMetricTemplates.producerTopicMetricsTemplates.metricGroupName;
        Set<String> clientIds = getKafkaClientIds(perTopicMetricGroupName);

        Set<KeyValue<String, String>> clientsTopicsList = new HashSet<>();
        for (String id : clientIds) {
            Set<KeyValue<String, String>> topicsPerClient = getClientTopicSet(id);
            clientsTopicsList.addAll(topicsPerClient);
        }
        Set<MetricName> metricsPerClientIdTopic = producerMetricTemplates.getMetricNamesProducerTopicGroup(clientsTopicsList);
        List<Collector.MetricFamilySamples> metricsDefinedAtRuntime = getMetricsPerClientIdTopic(perTopicMetricGroupName, metricsPerClientIdTopic);
        return metricsDefinedAtRuntime;
    }

    List<Collector.MetricFamilySamples> getMetricsPerBroker() {
        // producer-node-metrics
        String perBrokerMetricGroupName = producerMetricTemplates.perBrokerTemplates.metricGroupName;
        Set<String> clientIds = getKafkaClientIds(perBrokerMetricGroupName);
        Set<KeyValue<String, String>> clientsBrokerSet = new HashSet<>();
        for (String id : clientIds) {
            Set<KeyValue<String, String>> brokersPerClient = getClientNodeSet(id);
            clientsBrokerSet.addAll(brokersPerClient);
        }
        Set<MetricName> metricNamePerBrokerSet = producerMetricTemplates.getMetricNamesPerBrokerGroup(clientsBrokerSet);
        List<Collector.MetricFamilySamples> metricsPerBroker = getMetricsPerBroker(perBrokerMetricGroupName, metricNamePerBrokerSet);
        return metricsPerBroker;
    }

    @Override
    public List<Collector.MetricFamilySamples> getAllMetrics() {
        // producer-metrics
        List<Collector.MetricFamilySamples> metricsPerProducer =
                getMetricsPerClient(ProducerMetricTemplates.PRODUCER_METRIC_GROUP_NAME, producerMetricNames);
        // producer-topic-metrics
        List<Collector.MetricFamilySamples> metricsPerTopic = getMetricsProducerTopic();
        // producer-node-metrics
        List<Collector.MetricFamilySamples> metricsPerBroker = getMetricsPerBroker();

        return Stream
                .of(metricsPerProducer, metricsPerTopic, metricsPerBroker)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

}
