package com.example.kafka.streams;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.state.KeyValueStore;

import java.util.Arrays;
import java.util.Properties;

public class FavoriteColorApp {

    public static void main(String[] args) {
        Serde<String> stringSerde = Serdes.String();
        Serde<Long> longSerde = Serdes.Long();

        Properties config = new Properties();
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "favorite-color-streams");
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, stringSerde.getClass());
        config.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, stringSerde.getClass());
        config.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE);

        // we disable the cache to demonstrate all the "steps" involved in the transformation - not recommended in prod
        config.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, "0");

        StreamsBuilder builder = new StreamsBuilder();
        // Step 1: We create the topic of users keys to colours
        KStream<String, String> favoriteInput = builder.stream("favorite-color-input");

        KStream<String, String> usersAndColours = favoriteInput
                // 1 - we ensure that a comma is here as we will split on it
                .filter((key, value) -> value.contains(","))
                // 2 - we select a key that will be the user id (lowercase for safety)
                .selectKey((key, value) -> value.split(",")[0])
                // 3 - we get the colour from the value (lowercase for safety)
                .mapValues(value -> value.split(",")[1])
                // 4 - we filter undesired colours (could be a data sanitization step
                .filter((user, color) -> Arrays.asList("green", "blue", "red").contains(color));

        usersAndColours.to("user-keys-and-colours");

        // step 2 - we read that topic as a KTable so that updates are read correctly
        KTable<String, String> userAndColorsTable = builder.table("user-keys-and-colours");
        // step 3 - we count the occurrences of colours
        KTable<String, Long> favoriteColors = userAndColorsTable
        // 5 - we group by colour within the KTable
        .groupBy((user, color) -> new KeyValue<>(color, color))
                .count(Materialized.<String, Long, KeyValueStore<Bytes, byte[]>>as("CountsByColors")
                        .withKeySerde(stringSerde)
                        .withValueSerde(longSerde));

        // 6 - we output the results to a Kafka Topic - don't forget the serializers
        favoriteColors.toStream().to("favorite-color-output", Produced.with(stringSerde, longSerde));

        KafkaStreams streams = new KafkaStreams(builder.build(), config);

        // only do this in dev - not in prod
        streams.cleanUp();
        streams.start();

        // print the topology
        streams.localThreadsMetadata().forEach(System.out::println);

        // shutdown hook to correctly close the streams application
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }
}
