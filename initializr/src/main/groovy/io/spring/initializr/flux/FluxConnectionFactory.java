package io.spring.initializr.flux;

import org.eclipse.flux.client.FluxClient;
import org.eclipse.flux.client.MessageConnector;
import org.eclipse.flux.client.config.RabbitMQFluxConfig;

public class FluxConnectionFactory {
	
	public static MessageConnector create(String host, String username, String password) throws Exception {
		//return FluxClient.DEFAULT_INSTANCE.connect(new SocketIOFluxConfig(host, username, password));
		
		//Note: not using 
		//  - host: because this is the socketio webserver we are connecting directly to rabbitmq
		//  - password: direct rabbitmq connection requires no password (other than credentials included in rabbitURL)
		return FluxClient.DEFAULT_INSTANCE.connect(new RabbitMQFluxConfig(username)
			.setUri(RabbitMQFluxConfig.rabbitUrl()));
	}

}
