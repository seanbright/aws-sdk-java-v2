/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.urlconnection;

import static software.amazon.awssdk.http.Header.CONTENT_LENGTH;
import static software.amazon.awssdk.http.HttpStatusFamily.CLIENT_ERROR;
import static software.amazon.awssdk.http.HttpStatusFamily.SERVER_ERROR;
import static software.amazon.awssdk.utils.FunctionalUtils.invokeSafely;
import static software.amazon.awssdk.utils.NumericUtils.saturatedCast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.HttpStatusFamily;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.http.TlsKeyManagersProvider;
import software.amazon.awssdk.http.TlsTrustManagersProvider;
import software.amazon.awssdk.utils.AttributeMap;
import software.amazon.awssdk.utils.IoUtils;
import software.amazon.awssdk.utils.Logger;
import software.amazon.awssdk.utils.StringUtils;
import software.amazon.awssdk.utils.Validate;

/**
 * An implementation of {@link SdkHttpClient} that uses {@link HttpURLConnection} to communicate with the service. This is the
 * leanest synchronous client that optimizes for minimum dependencies and startup latency in exchange for having less
 * functionality than other implementations.
 *
 * <p>See software.amazon.awssdk.http.apache.ApacheHttpClient for an alternative implementation.</p>
 *
 * <p>This can be created via {@link #builder()}</p>
 */
@SdkPublicApi
public final class UrlConnectionHttpClient implements SdkHttpClient {

    private static final Logger log = Logger.loggerFor(UrlConnectionHttpClient.class);
    private static final String CLIENT_NAME = "UrlConnection";

    private final AttributeMap options;
    private final UrlConnectionFactory connectionFactory;
    private final ProxyConfiguration proxyConfiguration;

    private UrlConnectionHttpClient(AttributeMap options, UrlConnectionFactory connectionFactory, DefaultBuilder builder) {
        this.options = options;
        this.proxyConfiguration = builder != null ? builder.proxyConfiguration : null;

        if (connectionFactory != null) {
            this.connectionFactory = connectionFactory;
        } else {
            // Note: This socket factory MUST be reused between requests because the connection pool in the JVM is keyed by both
            // URL and SSLSocketFactory. If the socket factory is not reused, connections will not be reused between requests.
            SSLSocketFactory socketFactory = getSslContext(options).getSocketFactory();

            this.connectionFactory = url -> createDefaultConnection(url, socketFactory);
        }
    }

    private UrlConnectionHttpClient(AttributeMap options, UrlConnectionFactory connectionFactory) {
        this(options, connectionFactory, null);
    }

    public static Builder builder() {
        return new DefaultBuilder();
    }

    /**
     * Create a {@link HttpURLConnection} client with the default properties
     *
     * @return an {@link UrlConnectionHttpClient}
     */
    public static SdkHttpClient create() {
        return new DefaultBuilder().build();
    }

    /**
     * Use this method if you want to control the way a {@link HttpURLConnection} is created.
     * This will ignore SDK defaults like {@link SdkHttpConfigurationOption#CONNECTION_TIMEOUT}
     * and {@link SdkHttpConfigurationOption#READ_TIMEOUT}
     * @param connectionFactory a function that, given a {@link URI} will create an {@link HttpURLConnection}
     * @return an {@link UrlConnectionHttpClient}
     */
    public static SdkHttpClient create(UrlConnectionFactory connectionFactory) {
        return new UrlConnectionHttpClient(AttributeMap.empty(), connectionFactory);
    }

    @Override
    public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
        HttpURLConnection connection = createAndConfigureConnection(request);
        return new RequestCallable(connection, request);
    }

    @Override
    public void close() {
        // Nothing to close. The connections will be closed by closing the InputStreams.
    }

    @Override
    public String clientName() {
        return CLIENT_NAME;
    }

    private HttpURLConnection createAndConfigureConnection(HttpExecuteRequest request) {
        HttpURLConnection connection = connectionFactory.createConnection(request.httpRequest().getUri());
        request.httpRequest()
               .forEachHeader((key, values) -> values.forEach(value -> connection.setRequestProperty(key, value)));
        invokeSafely(() -> connection.setRequestMethod(request.httpRequest().method().name()));
        if (request.contentStreamProvider().isPresent()) {
            connection.setDoOutput(true);
        }

        // Disable following redirects since it breaks SDK error handling and matches Apache.
        // See: https://github.com/aws/aws-sdk-java-v2/issues/975
        connection.setInstanceFollowRedirects(false);

        request.httpRequest().firstMatchingHeader(CONTENT_LENGTH).map(Long::parseLong)
               .ifPresent(connection::setFixedLengthStreamingMode);

        return connection;
    }

    private HttpURLConnection createDefaultConnection(URI uri, SSLSocketFactory socketFactory) {

        Optional<Proxy> proxy = determineProxy(uri);
        HttpURLConnection connection = !proxy.isPresent() ?
                                       invokeSafely(() -> (HttpURLConnection) uri.toURL().openConnection())
                                                          :
                                       invokeSafely(() -> (HttpURLConnection) uri.toURL().openConnection(proxy.get()));

        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;

            if (options.get(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES)) {
                httpsConnection.setHostnameVerifier(NoOpHostNameVerifier.INSTANCE);
            }
            httpsConnection.setSSLSocketFactory(socketFactory);
        }

        if (proxy.isPresent() && shouldProxyAuthorize()) {
            connection.addRequestProperty("proxy-authorization", String.format("Basic %s", encodedAuthToken(proxyConfiguration)));
        }

        connection.setConnectTimeout(saturatedCast(options.get(SdkHttpConfigurationOption.CONNECTION_TIMEOUT).toMillis()));
        connection.setReadTimeout(saturatedCast(options.get(SdkHttpConfigurationOption.READ_TIMEOUT).toMillis()));

        return connection;
    }

    /**
     * If a proxy is configured with username+password, then set the proxy-authorization header to authorize ourselves with the
     * proxy
     */
    private static String encodedAuthToken(ProxyConfiguration proxyConfiguration) {

        String authToken = String.format("%s:%s", proxyConfiguration.username(), proxyConfiguration.password());
        return Base64.getEncoder().encodeToString(authToken.getBytes(StandardCharsets.UTF_8));
    }

    private boolean shouldProxyAuthorize() {
        return this.proxyConfiguration != null
               && ! StringUtils.isEmpty(this.proxyConfiguration.username())
               && ! StringUtils.isEmpty(this.proxyConfiguration.password());
    }

    private Optional<Proxy> determineProxy(URI uri) {
        if (isProxyEnabled() && isProxyHostIncluded(uri)) {
            return Optional.of(
                new Proxy(Proxy.Type.HTTP,
                          InetSocketAddress.createUnresolved(this.proxyConfiguration.host(), this.proxyConfiguration.port())));
        }
        return Optional.empty();
    }

    private boolean isProxyHostIncluded(URI uri) {
        return this.proxyConfiguration.nonProxyHosts()
                                      .stream()
                                      .noneMatch(uri.getHost().toLowerCase(Locale.getDefault())::matches);
    }

    private boolean isProxyEnabled() {
        return this.proxyConfiguration != null && this.proxyConfiguration.host() != null;
    }

    private SSLContext getSslContext(AttributeMap options) {
        Validate.isTrue(options.get(SdkHttpConfigurationOption.TLS_TRUST_MANAGERS_PROVIDER) == null ||
                        !options.get(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES),
                        "A TlsTrustManagerProvider can't be provided if TrustAllCertificates is also set");

        TrustManager[] trustManagers = null;
        if (options.get(SdkHttpConfigurationOption.TLS_TRUST_MANAGERS_PROVIDER) != null) {
            trustManagers = options.get(SdkHttpConfigurationOption.TLS_TRUST_MANAGERS_PROVIDER).trustManagers();
        }

        if (options.get(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES)) {
            log.warn(() -> "SSL Certificate verification is disabled. This is not a safe setting and should only be "
                           + "used for testing.");
            trustManagers = new TrustManager[] { TrustAllManager.INSTANCE };
        }

        TlsKeyManagersProvider provider = this.options.get(SdkHttpConfigurationOption.TLS_KEY_MANAGERS_PROVIDER);
        KeyManager[] keyManagers = provider.keyManagers();

        SSLContext context;
        try {
            context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagers, null);
            return context;
        } catch (NoSuchAlgorithmException | KeyManagementException ex) {
            throw new RuntimeException(ex.getMessage(), ex);
        }
    }

    private static class RequestCallable implements ExecutableHttpRequest {
        private final HttpURLConnection connection;
        private final HttpExecuteRequest request;

        /**
         * Whether we encountered the 'bug' in the way the HttpURLConnection handles 'Expect: 100-continue' cases. See
         * {@link #getAndHandle100Bug} for more information.
         */
        private boolean expect100BugEncountered = false;

        /**
         * Result cache for {@link #responseHasNoContent()}.
         */
        private Boolean responseHasNoContent;

        private RequestCallable(HttpURLConnection connection, HttpExecuteRequest request) {
            this.connection = connection;
            this.request = request;
        }

        @Override
        public HttpExecuteResponse call() throws IOException {
            connection.connect();

            Optional<ContentStreamProvider> requestContent = request.contentStreamProvider();

            if (requestContent.isPresent()) {
                Optional<OutputStream> outputStream = tryGetOutputStream();
                if (outputStream.isPresent()) {
                    IoUtils.copy(requestContent.get().newStream(), outputStream.get());
                }
            }

            int responseCode = getResponseCodeSafely(connection);
            boolean isErrorResponse = HttpStatusFamily.of(responseCode).isOneOf(CLIENT_ERROR, SERVER_ERROR);
            Optional<InputStream> responseContent = isErrorResponse ? tryGetErrorStream() : tryGetInputStream();
            AbortableInputStream responseBody = responseContent.map(AbortableInputStream::create).orElse(null);

            return HttpExecuteResponse.builder()
                                      .response(SdkHttpResponse.builder()
                                                           .statusCode(responseCode)
                                                           .statusText(connection.getResponseMessage())
                                                           // TODO: Don't ignore abort?
                                                           .headers(extractHeaders(connection))
                                                           .build())
                                      .responseBody(responseBody)
                                      .build();
        }

        private Optional<OutputStream> tryGetOutputStream() {
            return getAndHandle100Bug(() -> invokeSafely(connection::getOutputStream), false);
        }

        private Optional<InputStream> tryGetInputStream() {
            return getAndHandle100Bug(() -> invokeSafely(connection::getInputStream), true);
        }

        private Optional<InputStream> tryGetErrorStream() {
            InputStream result = invokeSafely(connection::getErrorStream);
            if (result == null && expect100BugEncountered) {
                log.debug(() -> "The response payload has been dropped because of a limitation of the JDK's URL Connection "
                                + "HTTP client, resulting in a less descriptive SDK exception error message. Using "
                                + "the Apache HTTP client removes this limitation.");
            }
            return Optional.ofNullable(result);
        }

        /**
         * This handles a bug in {@link HttpURLConnection#getOutputStream()} and {@link HttpURLConnection#getInputStream()}
         * where these methods will throw a ProtocolException if we sent an "Expect: 100-continue" header, and the
         * service responds with something other than a 100.
         *
         * HttpUrlConnection still gives us access to the response code and headers when this bug is encountered, so our
         * handling of the bug is:
         * <ol>
         *     <li>If the service returned a response status or content length that indicates there was no response payload,
         *     we ignore that we couldn't read the response payload, and just return the response with what we have.</li>
         *     <li>If the service returned a payload and we can't read it because of the bug, we throw an exception for
         *     non-failure cases (2xx, 3xx) or log and return the response without the payload for failure cases (4xx or 5xx)
         *     .</li>
         * </ol>
         */
        private <T> Optional<T> getAndHandle100Bug(Supplier<T> supplier, boolean failOn100Bug) {
            try {
                return Optional.ofNullable(supplier.get());
            } catch (RuntimeException e) {
                if (!exceptionCausedBy100HandlingBug(e)) {
                    throw e;
                }

                if (responseHasNoContent()) {
                    return Optional.empty();
                }

                expect100BugEncountered = true;

                if (!failOn100Bug) {
                    return Optional.empty();
                }

                int responseCode = invokeSafely(connection::getResponseCode);
                String message = "Unable to read response payload, because service returned response code "
                                 + responseCode + " to an Expect: 100-continue request. Using another HTTP client "
                                 + "implementation (e.g. Apache) removes this limitation.";
                throw new UncheckedIOException(new IOException(message, e));
            }
        }

        private boolean exceptionCausedBy100HandlingBug(RuntimeException e) {
            return requestWasExpect100Continue() &&
                   e.getMessage() != null &&
                   e.getMessage().startsWith("java.net.ProtocolException: Server rejected operation");
        }

        private Boolean requestWasExpect100Continue() {
            return request.httpRequest()
                          .firstMatchingHeader("Expect")
                          .map(expect -> expect.equalsIgnoreCase("100-continue"))
                          .orElse(false);
        }

        private boolean responseHasNoContent() {
            // We cannot account for chunked encoded responses, because we only have access to headers and response code here,
            // so we assume chunked encoded responses DO have content.
            if (responseHasNoContent == null) {
                responseHasNoContent = responseNeverHasPayload(invokeSafely(connection::getResponseCode)) ||
                                       Objects.equals(connection.getHeaderField("Content-Length"), "0") ||
                                       Objects.equals(connection.getRequestMethod(), "HEAD");
            }
            return responseHasNoContent;
        }

        private boolean responseNeverHasPayload(int responseCode) {
            return responseCode == 204 || responseCode == 304 || (responseCode >= 100 && responseCode < 200);
        }

        /**
         * {@link sun.net.www.protocol.http.HttpURLConnection#getInputStream0()} has been observed to intermittently throw
         * {@link NullPointerException}s for reasons that still require further investigation, but are assumed to be due to a
         * bug in the JDK. Propagating such NPEs is confusing for users and are not subject to being retried on by the default 
         * retry policy configuration, so instead we bias towards propagating these as {@link IOException}s.
         * <p>
         * TODO: Determine precise root cause of intermittent NPEs, submit JDK bug report if applicable, and consider applying
         * this behavior only on unpatched JVM runtime versions.
         */
        private static int getResponseCodeSafely(HttpURLConnection connection) throws IOException {
            Validate.paramNotNull(connection, "connection");
            try {
                return connection.getResponseCode();
            } catch (NullPointerException e) {
                throw new IOException("Unexpected NullPointerException when trying to read response from HttpURLConnection", e);
            }
        }

        private Map<String, List<String>> extractHeaders(HttpURLConnection response) {
            return response.getHeaderFields().entrySet().stream()
                           .filter(e -> e.getKey() != null)
                           .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        @Override
        public void abort() {
            connection.disconnect();
        }
    }

    /**
     * A builder for an instance of {@link SdkHttpClient} that uses JDKs build-in {@link java.net.URLConnection} HTTP
     * implementation. A builder can be created via {@link #builder()}.
     *
     * <pre class="brush: java">
     * SdkHttpClient httpClient = UrlConnectionHttpClient.builder()
     * .socketTimeout(Duration.ofSeconds(10))
     * .connectionTimeout(Duration.ofSeconds(1))
     * .build();
     * </pre>
     */
    public interface Builder extends SdkHttpClient.Builder<UrlConnectionHttpClient.Builder> {

        /**
         * The amount of time to wait for data to be transferred over an established, open connection before the connection is
         * timed out. A duration of 0 means infinity, and is not recommended.
         */
        Builder socketTimeout(Duration socketTimeout);

        /**
         * The amount of time to wait when initially establishing a connection before giving up and timing out. A duration of 0
         * means infinity, and is not recommended.
         */
        Builder connectionTimeout(Duration connectionTimeout);

        /**
         * Configure the {@link TlsKeyManagersProvider} that will provide the {@link javax.net.ssl.KeyManager}s to use
         * when constructing the SSL context.
         */
        Builder tlsKeyManagersProvider(TlsKeyManagersProvider tlsKeyManagersProvider);

        /**
         * Configure the {@link TlsTrustManagersProvider} that will provide the {@link javax.net.ssl.TrustManager}s to use
         * when constructing the SSL context.
         */
        Builder tlsTrustManagersProvider(TlsTrustManagersProvider tlsTrustManagersProvider);

        /**
         * Configuration that defines how to communicate via an HTTP proxy.
         * @param proxyConfiguration proxy configuration builder object.
         * @return the builder for method chaining.
         */
        Builder proxyConfiguration(ProxyConfiguration proxyConfiguration);

        /**
         * Sets the http proxy configuration to use for this client.
         *
         * @param proxyConfigurationBuilderConsumer The consumer of the proxy configuration builder object.
         * @return the builder for method chaining.
         */
        Builder proxyConfiguration(Consumer<ProxyConfiguration.Builder> proxyConfigurationBuilderConsumer);


    }

    private static final class DefaultBuilder implements Builder {
        private final AttributeMap.Builder standardOptions = AttributeMap.builder();
        private ProxyConfiguration proxyConfiguration;

        private DefaultBuilder() {
        }

        /**
         * Sets the read timeout to a specified timeout. A timeout of zero is interpreted as an infinite timeout.
         *
         * @param socketTimeout the timeout as a {@link Duration}
         * @return this object for method chaining
         */
        @Override
        public Builder socketTimeout(Duration socketTimeout) {
            standardOptions.put(SdkHttpConfigurationOption.READ_TIMEOUT, socketTimeout);
            return this;
        }

        public void setSocketTimeout(Duration socketTimeout) {
            socketTimeout(socketTimeout);
        }

        /**
         * Sets the connect timeout to a specified timeout. A timeout of zero is interpreted as an infinite timeout.
         *
         * @param connectionTimeout the timeout as a {@link Duration}
         * @return this object for method chaining
         */
        @Override
        public Builder connectionTimeout(Duration connectionTimeout) {
            standardOptions.put(SdkHttpConfigurationOption.CONNECTION_TIMEOUT, connectionTimeout);
            return this;
        }

        public void setConnectionTimeout(Duration connectionTimeout) {
            connectionTimeout(connectionTimeout);
        }

        @Override
        public Builder tlsKeyManagersProvider(TlsKeyManagersProvider tlsKeyManagersProvider) {
            standardOptions.put(SdkHttpConfigurationOption.TLS_KEY_MANAGERS_PROVIDER, tlsKeyManagersProvider);
            return this;
        }

        public void setTlsKeyManagersProvider(TlsKeyManagersProvider tlsKeyManagersProvider) {
            tlsKeyManagersProvider(tlsKeyManagersProvider);
        }

        @Override
        public Builder tlsTrustManagersProvider(TlsTrustManagersProvider tlsTrustManagersProvider) {
            standardOptions.put(SdkHttpConfigurationOption.TLS_TRUST_MANAGERS_PROVIDER, tlsTrustManagersProvider);
            return this;
        }

        public void setTlsTrustManagersProvider(TlsTrustManagersProvider tlsTrustManagersProvider) {
            tlsTrustManagersProvider(tlsTrustManagersProvider);
        }

        @Override
        public Builder proxyConfiguration(ProxyConfiguration proxyConfiguration) {
            this.proxyConfiguration = proxyConfiguration;
            return this;
        }

        @Override
        public Builder proxyConfiguration(Consumer<ProxyConfiguration.Builder> proxyConfigurationBuilderConsumer) {
            ProxyConfiguration.Builder builder = ProxyConfiguration.builder();
            proxyConfigurationBuilderConsumer.accept(builder);
            return proxyConfiguration(builder.build());
        }

        public void setProxyConfiguration(ProxyConfiguration proxyConfiguration) {
            proxyConfiguration(proxyConfiguration);
        }


        /**
         * Used by the SDK to create a {@link SdkHttpClient} with service-default values if no other values have been configured
         *
         * @param serviceDefaults Service specific defaults. Keys will be one of the constants defined in
         * {@link SdkHttpConfigurationOption}.
         * @return an instance of {@link SdkHttpClient}
         */
        @Override
        public SdkHttpClient buildWithDefaults(AttributeMap serviceDefaults) {
            return new UrlConnectionHttpClient(standardOptions.build()
                                                              .merge(serviceDefaults)
                                                              .merge(SdkHttpConfigurationOption.GLOBAL_HTTP_DEFAULTS),
                                               null, this);
        }
    }

    private static class NoOpHostNameVerifier implements HostnameVerifier {

        static final NoOpHostNameVerifier INSTANCE = new NoOpHostNameVerifier();

        @Override
        public boolean verify(String s, SSLSession sslSession) {
            return true;
        }
    }

    /**
     * Insecure trust manager to trust all certs. Should only be used for testing.
     */
    private static class TrustAllManager implements X509TrustManager {

        private static final TrustAllManager INSTANCE = new TrustAllManager();

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) {
            log.debug(() -> "Accepting a client certificate: " + x509Certificates[0].getSubjectDN());
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) {
            log.debug(() -> "Accepting a server certificate: " + x509Certificates[0].getSubjectDN());
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
