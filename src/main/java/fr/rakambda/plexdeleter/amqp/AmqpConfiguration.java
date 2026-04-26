package fr.rakambda.plexdeleter.amqp;

import fr.rakambda.plexdeleter.config.ApplicationConfiguration;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;

@Configuration
@EnableRabbit
public class AmqpConfiguration{
	private final AmqpConstants amqpConstants;
	private final ApplicationConfiguration applicationConfiguration;
	
	@Autowired
	public AmqpConfiguration(AmqpConstants amqpConstants, ApplicationConfiguration applicationConfiguration){
		this.amqpConstants = amqpConstants;
		this.applicationConfiguration = applicationConfiguration;
	}
	
	@Bean
	@Qualifier("exchangeProcess")
	public DirectExchange exchangeProcess(){
		return ExchangeBuilder.directExchange(prefixed(amqpConstants.EXCHANGE_PROCESS)).durable(true).build();
	}
	
	@Bean
	@Qualifier("exchangeDeadLetter")
	public DirectExchange exchangeDeadLetter(){
		return ExchangeBuilder.directExchange(prefixed(amqpConstants.EXCHANGE_DEAD_LETTER)).durable(true).build();
	}
	
	@Bean
	@Qualifier("queueProcessRadarr")
	public Queue queueProcessRadarr(){
		return QueueBuilder.durable(prefixed(amqpConstants.QUEUE_PROCESS_RADARR))
				.withArgument(amqpConstants.HEADER_X_DEAD_LETTER_EXCHANGE, prefixed(amqpConstants.EXCHANGE_DEAD_LETTER))
				.withArgument(amqpConstants.HEADER_X_DEAD_LETTER_ROUTING_KEY, amqpConstants.ROUTING_KEY_DEAD_LETTER_RADARR)
				.build();
	}
	
	@Bean
	@Qualifier("queueDeadLetterRadarr")
	public Queue queueDeadLetterRadarr(){
		return QueueBuilder.durable(prefixed(amqpConstants.QUEUE_DEAD_LETTER_RADARR))
				.withArgument(amqpConstants.HEADER_X_DEAD_LETTER_EXCHANGE, prefixed(amqpConstants.EXCHANGE_PROCESS))
				.withArgument(amqpConstants.HEADER_X_DEAD_LETTER_ROUTING_KEY, amqpConstants.ROUTING_KEY_PROCESS_RADARR)
				.withArgument(amqpConstants.HEADER_X_MESSAGE_TTL, applicationConfiguration.getAmqp().getRequeueDelay().toMillis())
				.build();
	}
	
	@Bean
	@Qualifier("queueProcessSonarr")
	public Queue queueProcessSonarr(){
		return QueueBuilder.durable(prefixed(amqpConstants.QUEUE_PROCESS_SONARR))
				.withArgument(amqpConstants.HEADER_X_DEAD_LETTER_EXCHANGE, prefixed(amqpConstants.EXCHANGE_DEAD_LETTER))
				.withArgument(amqpConstants.HEADER_X_DEAD_LETTER_ROUTING_KEY, amqpConstants.ROUTING_KEY_DEAD_LETTER_SONARR)
				.build();
	}
	
	@Bean
	@Qualifier("queueDeadLetterSonarr")
	public Queue queueDeadLetterSonarr(){
		return QueueBuilder.durable(prefixed(amqpConstants.QUEUE_DEAD_LETTER_SONARR))
				.withArgument(amqpConstants.HEADER_X_DEAD_LETTER_EXCHANGE, prefixed(amqpConstants.EXCHANGE_PROCESS))
				.withArgument(amqpConstants.HEADER_X_DEAD_LETTER_ROUTING_KEY, amqpConstants.ROUTING_KEY_PROCESS_SONARR)
				.withArgument(amqpConstants.HEADER_X_MESSAGE_TTL, applicationConfiguration.getAmqp().getRequeueDelay().toMillis())
				.build();
	}
	
	@Bean
	@Qualifier("queueProcessTautulli")
	public Queue queueProcessTautulli(){
		return QueueBuilder.durable(prefixed(amqpConstants.QUEUE_PROCESS_TAUTULLI))
				.withArgument(amqpConstants.HEADER_X_DEAD_LETTER_EXCHANGE, prefixed(amqpConstants.EXCHANGE_DEAD_LETTER))
				.withArgument(amqpConstants.HEADER_X_DEAD_LETTER_ROUTING_KEY, amqpConstants.ROUTING_KEY_DEAD_LETTER_TAUTULLI)
				.withArgument(amqpConstants.HEADER_X_MESSAGE_TTL, Duration.ofMinutes(1).toMillis())
				.build();
	}
	
	@Bean
	@Qualifier("queueDeadLetterTautulli")
	public Queue queueDeadLetterTautulli(){
		return QueueBuilder.durable(prefixed(amqpConstants.QUEUE_DEAD_LETTER_TAUTULLI))
				.withArgument(amqpConstants.HEADER_X_DEAD_LETTER_EXCHANGE, prefixed(amqpConstants.EXCHANGE_PROCESS))
				.withArgument(amqpConstants.HEADER_X_DEAD_LETTER_ROUTING_KEY, amqpConstants.ROUTING_KEY_PROCESS_TAUTULLI)
				.withArgument(amqpConstants.HEADER_X_MESSAGE_TTL, applicationConfiguration.getAmqp().getRequeueDelay().toMillis())
				.build();
	}
	
	@Bean
	@Qualifier("bindingProcessRadarr")
	public Binding bindingProcessRadarr(@Qualifier("queueProcessRadarr") Queue queue, @Qualifier("exchangeProcess") DirectExchange exchange){
		return BindingBuilder.bind(queue).to(exchange).with(amqpConstants.ROUTING_KEY_PROCESS_RADARR);
	}
	
	@Bean
	@Qualifier("bindingProcessSonarr")
	public Binding bindingProcessSonarr(@Qualifier("queueProcessSonarr") Queue queue, @Qualifier("exchangeProcess") DirectExchange exchange){
		return BindingBuilder.bind(queue).to(exchange).with(amqpConstants.ROUTING_KEY_PROCESS_SONARR);
	}
	
	@Bean
	@Qualifier("bindingProcessTautulli")
	public Binding bindingProcessTautulli(@Qualifier("queueProcessTautulli") Queue queue, @Qualifier("exchangeProcess") DirectExchange exchange){
		return BindingBuilder.bind(queue).to(exchange).with(amqpConstants.ROUTING_KEY_PROCESS_TAUTULLI);
	}
	
	@Bean
	@Qualifier("bindingDeadLetterRadarr")
	public Binding bindingDeadLetterRadarr(@Qualifier("queueDeadLetterRadarr") Queue queue, @Qualifier("exchangeDeadLetter") DirectExchange exchange){
		return BindingBuilder.bind(queue).to(exchange).with(amqpConstants.ROUTING_KEY_DEAD_LETTER_RADARR);
	}
	
	@Bean
	@Qualifier("bindingDeadLetterSonarr")
	public Binding bindingDeadLetterSonarr(@Qualifier("queueDeadLetterSonarr") Queue queue, @Qualifier("exchangeDeadLetter") DirectExchange exchange){
		return BindingBuilder.bind(queue).to(exchange).with(amqpConstants.ROUTING_KEY_DEAD_LETTER_SONARR);
	}
	
	@Bean
	@Qualifier("bindingDeadLetterTautulli")
	public Binding bindingDeadLetterTautulli(@Qualifier("queueDeadLetterTautulli") Queue queue, @Qualifier("exchangeDeadLetter") DirectExchange exchange){
		return BindingBuilder.bind(queue).to(exchange).with(amqpConstants.ROUTING_KEY_DEAD_LETTER_TAUTULLI);
	}
	
	@Bean
	public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, JacksonJsonMessageConverter messageConverter){
		var backOff = new ExponentialBackOff();
		backOff.setInitialInterval(1000);
		backOff.setMultiplier(10);
		backOff.setMaxInterval(60000);
		
		var retryPolicy = RetryPolicy.builder()
				.backOff(backOff)
				.build();
		
		var retryTemplate = new RetryTemplate(retryPolicy);
		
		var rabbitTemplate = new RabbitTemplate(connectionFactory);
		rabbitTemplate.setMessageConverter(messageConverter);
		rabbitTemplate.setRetryTemplate(retryTemplate);
		return rabbitTemplate;
	}
	
	@Bean
	public JacksonJsonMessageConverter producerJackson2MessageConverter(JsonMapper jsonMapper){
		return new JacksonJsonMessageConverter(jsonMapper);
	}
	
	@NonNull
	public String prefixed(@NonNull String name){
		return applicationConfiguration.getAmqp().getPrefix() + "." + name;
	}
}
