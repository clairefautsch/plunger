package de.galan.plunger.command.kafka;

import static de.galan.commons.util.Sugar.*;
import static java.nio.charset.StandardCharsets.*;
import static org.apache.commons.lang3.StringUtils.*;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Properties;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.serialization.StringDeserializer;

import com.google.common.base.StandardSystemProperty;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

import de.galan.commons.time.Durations;
import de.galan.commons.time.Instants;
import de.galan.plunger.command.CommandException;
import de.galan.plunger.command.generic.AbstractCatCommand;
import de.galan.plunger.domain.Message;
import de.galan.plunger.domain.PlungerArguments;


/**
 * Retrieves messages from a Kafka broker.
 */
public class KafkaCatCommand extends AbstractCatCommand {

	private KafkaConsumer<String, String> consumer;
	private Iterator<ConsumerRecord<String, String>> recordIterator;
	int timeout = 1000;
	String groupId;
	String autoOffsetReset;
	private String clientId;
	private boolean commit;


	@Override
	protected void initialize(PlungerArguments pa) throws CommandException {
		super.initialize(pa);
		clientId = "plunger-" + StandardSystemProperty.USER_NAME.value() + "-" + System.currentTimeMillis();
		groupId = KafkaUtils.groupId(pa.getTarget());
		autoOffsetReset = optional(pa.getTarget().getParameterValue("autoOffsetReset")).orElse("earliest");
		commit = pa.containsCommandArgument("r");
	}


	@Override
	protected void beforeFirstMessage(PlungerArguments pa) throws CommandException {
		Properties props = new Properties();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaUtils.brokers(pa.getTarget()));
		props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
		props.put(ConsumerConfig.EXCLUDE_INTERNAL_TOPICS_CONFIG, "true");
		props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Long.toString(determineMaxPollRecords(pa)));

		Integer maxPartitionFetchBytes = determineMaxPartitionFetchBytes(pa);
		if (maxPartitionFetchBytes != null) {
			props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, Integer.toString(maxPartitionFetchBytes));
		}

		consumer = new KafkaConsumer<>(props);
		consumer.subscribe(Arrays.asList(pa.getTarget().getDestination()));
		String timeoutDuration = pa.getTarget().getParameterValue("timeout");
		if (isNotBlank(timeoutDuration)) {
			timeout = Durations.dehumanize(timeoutDuration).intValue();
		}
	}


	/**
	 * Returns the maxPartitionFetchBytes url argument, which could overwrite the default kakfa value for
	 * 'max.partition.fetch.bytes'.
	 */
	private Integer determineMaxPartitionFetchBytes(PlungerArguments pa) {
		String param = pa.getTarget().getParameterValue("maxPartitionFetchBytes");
		if (isNotBlank(param)) {
			return Ints.tryParse(param);
		}
		return null;
	}


	/**
	 * Returns the "maxPollRecords" url argument, which will override the default of 1 for "max.poll.records". if the size
	 * is larger then the limit "-n", it will be reduced to this.
	 */
	private Long determineMaxPollRecords(PlungerArguments pa) {
		Long maxPollRecords = Longs.tryParse(optional(pa.getTarget().getParameterValue("maxPollRecords")).orElse("1"));
		Long limit = pa.getCommandArgumentLong("n");
		if (limit != null && limit < maxPollRecords) {
			maxPollRecords = limit;
		}
		return maxPollRecords;
	}


	@Override
	protected Message getNextMessage(PlungerArguments pa) throws CommandException {
		Message result = null;
		if (recordIterator == null || !recordIterator.hasNext()) {
			ConsumerRecords<String, String> records = consumer.poll(timeout);
			recordIterator = records.iterator();
		}

		if (recordIterator != null && recordIterator.hasNext()) {
			ConsumerRecord<String, String> record = recordIterator.next();
			Message msg = new Message();
			msg.setBody(record.value());
			if (!pa.containsCommandArgument("p")) { // exclude properties or not
				msg.putProperty("kafka.key", record.key());
				msg.putProperty("kafka.offset", record.offset());
				msg.putProperty("kafka.partition", record.partition());
				if (record.timestampType() != null && !record.timestampType().equals(TimestampType.NO_TIMESTAMP_TYPE)) {
					msg.putProperty("kafka.timestamp", Instants.from(Instants.instant(record.timestamp())).toStringUtc());
					msg.putProperty("kafka.timestamp_type", record.timestampType().toString());
				}

				for (Header header: record.headers()) {
					msg.putProperty(header.key(), new String(header.value(), UTF_8));
				}

			}
			result = msg;
		}
		if (commit && recordIterator != null && result != null && !recordIterator.hasNext()) {
			consumer.commitSync();
		}

		return result;
	}


	@Override
	protected boolean isSystemHeader(String headerName) {
		// kafka does not provider own header information, only payload. Meta-data is provided as header instead.
		return startsWith(headerName, "kafka.");
	}


	@Override
	protected void close() {
		if (consumer != null) {
			consumer.unsubscribe();
			consumer.close();
		}
	}

}
