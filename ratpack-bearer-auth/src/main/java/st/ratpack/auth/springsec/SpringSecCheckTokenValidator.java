package st.ratpack.auth.springsec;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ratpack.exec.Promise;
import ratpack.http.HttpUrlBuilder;
import ratpack.http.client.HttpClient;
import ratpack.http.client.ReceivedResponse;
import st.ratpack.auth.OAuthToken;
import st.ratpack.auth.TokenValidator;
import st.ratpack.auth.User;

import java.net.URI;
import java.util.Base64;
import java.util.Optional;

public class SpringSecCheckTokenValidator implements TokenValidator {

	private final HttpClient httpClient;
	private final SpringSecCheckAuthModule.Config config;
	private static Logger logger = LoggerFactory.getLogger(SpringSecCheckTokenValidator.class);
	private final ObjectMapper objectMapper;

	public SpringSecCheckTokenValidator(SpringSecCheckAuthModule.Config config, HttpClient httpClient, ObjectMapper objectMapper) {
		this.httpClient = httpClient;
		this.config = config;
		this.objectMapper = objectMapper;
	}

	@Override
	public Promise<Optional<OAuthToken>> validate(String token) {

		URI uri = HttpUrlBuilder.base(config.getHost())
			.path("oauth/check_token")
			.params("token", token)
			.build();

		Promise<ReceivedResponse> resp = httpClient.get(uri, rs -> {
			rs.redirects(0);
			rs.headers(headers -> {
				headers.add(HttpHeaderNames.AUTHORIZATION, buildBasicAuthHeader(config.getUser(), config.getPassword()));
				headers.add(HttpHeaderNames.ACCEPT, "application/json");
			});
		});

		return Promise.of(downstream ->
			resp.onError(t -> {
				logger.error("Failed to check auth token.", t);
				downstream.success(Optional.<OAuthToken>empty());
			}).then(response -> {
				if (response.getStatusCode() != 200) {
					logger.error("Got Status: " + response.getStatusCode());
					downstream.success(Optional.<OAuthToken>empty());
				} else {
					OAuthToken oAuthToken = null;

					try {
						CheckTokenResponse tokenResponse = objectMapper.readValue(response.getBody().getInputStream(), CheckTokenResponse.class);

						if (tokenResponse.getClient_id() != null && !(tokenResponse.getClient_id().isEmpty())) {
							oAuthToken = new OAuthToken();

							oAuthToken.setClientId(tokenResponse.getClient_id());
							oAuthToken.setScopes(tokenResponse.getScope());

							if (tokenResponse.getUser_name() != null && !(tokenResponse.getUser_name().isEmpty())) {
								//There is a use so we add an optional user to the token. This won't be there in the case of a client only oauth token.
								User user = new User();
								user.setUsername(tokenResponse.getUser_name());
								user.setAuthorities(tokenResponse.getAuthorities());
								oAuthToken.setUser(Optional.of(user));
							} else {
								oAuthToken.setUser(Optional.<User>empty());
							}
						}

						downstream.success(Optional.ofNullable(oAuthToken));
					} catch (JsonParseException ex) {
						logger.error("Could not parse body");
						downstream.error(ex);
					}
				}
			})
		);
	}

	private String buildBasicAuthHeader(String user, String password) {
		String encodedCreds = Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
		return "Basic " + encodedCreds;
	}

}
