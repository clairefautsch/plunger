package de.galan.plunger.command.kafka;

import static de.galan.commons.util.Sugar.*;
import static java.nio.charset.StandardCharsets.*;
import static org.apache.commons.lang3.StringUtils.*;

import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringSerializer;

import com.google.common.primitives.Ints;

import de.galan.plunger.command.CommandException;
import de.galan.plunger.command.generic.AbstractPutCommand;
import de.galan.plunger.domain.Message;
import de.galan.plunger.domain.PlungerArguments;


/**
 * Writes messages to a destination on a Kafka broker.
 */
public class KafkaPutCommand extends AbstractPutCommand {

	private Producer<String, String> producer;


	@Override
	protected void initialize(PlungerArguments pa) throws CommandException {
		super.initialize(pa);
		Properties props = new Properties();
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaUtils.brokers(pa.getTarget()));
		props.put(ProducerConfig.ACKS_CONFIG, determineAcksConfig(pa));
		props.put(ProducerConfig.RETRIES_CONFIG, 0);
		props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
		props.put(ProducerConfig.LINGER_MS_CONFIG, 1);
		props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

		Integer maxRequestSize = determineMaxRequestSize(pa);
		if (maxRequestSize != null) {
			props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, Integer.toString(maxRequestSize));
		}

		producer = new KafkaProducer<>(props);
	}


	/** Returns the maxRequestSize url argument, which could overwrite the default kakfa value for 'max.request.size'. */
	private Integer determineMaxRequestSize(PlungerArguments pa) {
		String param = pa.getTarget().getParameterValue("maxRequestSize");
		if (isNotBlank(param)) {
			return Ints.tryParse(param);
		}
		return null;
	}


	private String determineAcksConfig(PlungerArguments pa) {
		return isNotBlank(pa.getCommandArgument("acks")) ? pa.getCommandArgument("acks") : "all";
	}


	@Override
	protected void sendMessage(PlungerArguments pa, Message message, long count) throws CommandException {
		try {
			Headers headers = mapHeader(message);
			String topic = pa.getTarget().getDestination();
			producer.send(new ProducerRecord<String, String>(topic, null, getKey(message, pa), message.getBody(), headers)).get();
		}
		catch (InterruptedException | ExecutionException ex) {
			throw new CommandException("Failed sending record: " + ex.getMessage(), ex);
		}
	}


	private Headers mapHeader(Message message) {
		Headers headers = new RecordHeaders();
		if (message.getProperties() != null) {
			for (Entry<String, Object> entry: message.getProperties().entrySet()) {
				headers.add(new RecordHeader(entry.getKey(), entry.getValue().toString().getBytes(UTF_8)));
			}
		}
		return headers;
	}


	private String getKey(Message message, PlungerArguments pa) {
		String targetKey = trimToNull(pa.getTarget().getParameterValue("key"));
		if (targetKey == null && pa.getTarget().containsParameter("key")) {
			return null; // "key=" user will overwrite keys from message with empty key
		}
		return optional(targetKey).orElseGet(() -> trimToNull(message.getPropertyString("kafka.key")));
	}


	@Override
	protected void close() {
		if (producer != null) {
			producer.close();
		}
	}

}
