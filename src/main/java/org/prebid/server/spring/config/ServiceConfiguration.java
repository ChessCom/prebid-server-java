package org.prebid.server.spring.config;

import de.malkusch.whoisServerList.publicSuffixList.PublicSuffixList;
import de.malkusch.whoisServerList.publicSuffixList.PublicSuffixListFactory;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileSystem;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.net.JksOptions;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.auction.AmpResponsePostProcessor;
import org.prebid.server.auction.BidResponseCreator;
import org.prebid.server.auction.BidResponsePostProcessor;
import org.prebid.server.auction.DebugResolver;
import org.prebid.server.auction.ExchangeService;
import org.prebid.server.auction.FpdResolver;
import org.prebid.server.auction.ImplicitParametersExtractor;
import org.prebid.server.auction.InterstitialProcessor;
import org.prebid.server.auction.IpAddressHelper;
import org.prebid.server.auction.OrtbTypesResolver;
import org.prebid.server.auction.PrivacyEnforcementService;
import org.prebid.server.auction.StoredRequestProcessor;
import org.prebid.server.auction.StoredResponseProcessor;
import org.prebid.server.auction.SupplyChainResolver;
import org.prebid.server.auction.TimeoutResolver;
import org.prebid.server.auction.UidUpdater;
import org.prebid.server.auction.VideoResponseFactory;
import org.prebid.server.auction.VideoStoredRequestProcessor;
import org.prebid.server.auction.WinningBidComparatorFactory;
import org.prebid.server.auction.adjustment.BidAdjustmentFactorResolver;
import org.prebid.server.auction.categorymapping.BasicCategoryMappingService;
import org.prebid.server.auction.categorymapping.CategoryMappingService;
import org.prebid.server.auction.categorymapping.NoOpCategoryMappingService;
import org.prebid.server.auction.mediatypeprocessor.BidderMediaTypeProcessor;
import org.prebid.server.auction.mediatypeprocessor.MediaTypeProcessor;
import org.prebid.server.auction.mediatypeprocessor.NoOpMediaTypeProcessor;
import org.prebid.server.auction.privacycontextfactory.AmpPrivacyContextFactory;
import org.prebid.server.auction.requestfactory.AmpRequestFactory;
import org.prebid.server.auction.requestfactory.AuctionRequestFactory;
import org.prebid.server.auction.requestfactory.Ortb2ImplicitParametersResolver;
import org.prebid.server.auction.requestfactory.Ortb2RequestFactory;
import org.prebid.server.auction.requestfactory.VideoRequestFactory;
import org.prebid.server.auction.versionconverter.BidRequestOrtbVersionConversionManager;
import org.prebid.server.auction.versionconverter.BidRequestOrtbVersionConverterFactory;
import org.prebid.server.bidder.BidderCatalog;
import org.prebid.server.bidder.BidderDeps;
import org.prebid.server.bidder.BidderErrorNotifier;
import org.prebid.server.bidder.BidderRequestCompletionTrackerFactory;
import org.prebid.server.bidder.HttpBidderRequestEnricher;
import org.prebid.server.bidder.HttpBidderRequester;
import org.prebid.server.cache.CacheService;
import org.prebid.server.cache.model.CacheTtl;
import org.prebid.server.cookie.CookieSyncService;
import org.prebid.server.cookie.CoopSyncProvider;
import org.prebid.server.cookie.PrioritizedCoopSyncProvider;
import org.prebid.server.cookie.UidsCookieService;
import org.prebid.server.currency.CurrencyConversionService;
import org.prebid.server.deals.DealsService;
import org.prebid.server.deals.UserAdditionalInfoService;
import org.prebid.server.deals.events.ApplicationEventService;
import org.prebid.server.events.EventsService;
import org.prebid.server.execution.TimeoutFactory;
import org.prebid.server.floors.PriceFloorAdjuster;
import org.prebid.server.floors.PriceFloorEnforcer;
import org.prebid.server.floors.PriceFloorProcessor;
import org.prebid.server.floors.PriceFloorsConfigResolver;
import org.prebid.server.geolocation.CountryCodeMapper;
import org.prebid.server.hooks.execution.HookStageExecutor;
import org.prebid.server.identity.IdGenerator;
import org.prebid.server.identity.NoneIdGenerator;
import org.prebid.server.identity.UUIDIdGenerator;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.json.JsonMerger;
import org.prebid.server.log.CriteriaLogManager;
import org.prebid.server.log.CriteriaManager;
import org.prebid.server.log.HttpInteractionLogger;
import org.prebid.server.log.LoggerControlKnob;
import org.prebid.server.metric.Metrics;
import org.prebid.server.optout.GoogleRecaptchaVerifier;
import org.prebid.server.privacy.HostVendorTcfDefinerService;
import org.prebid.server.privacy.PrivacyExtractor;
import org.prebid.server.privacy.gdpr.TcfDefinerService;
import org.prebid.server.settings.ApplicationSettings;
import org.prebid.server.settings.model.BidValidationEnforcement;
import org.prebid.server.spring.config.model.ExternalConversionProperties;
import org.prebid.server.spring.config.model.HttpClientCircuitBreakerProperties;
import org.prebid.server.spring.config.model.HttpClientProperties;
import org.prebid.server.util.VersionInfo;
import org.prebid.server.validation.BidderParamValidator;
import org.prebid.server.validation.RequestValidator;
import org.prebid.server.validation.ResponseBidValidator;
import org.prebid.server.validation.VideoRequestValidator;
import org.prebid.server.vast.VastModifier;
import org.prebid.server.version.PrebidVersionProvider;
import org.prebid.server.vertx.http.BasicHttpClient;
import org.prebid.server.vertx.http.CircuitBreakerSecuredHttpClient;
import org.prebid.server.vertx.http.HttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;

import javax.validation.constraints.Min;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
public class ServiceConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ServiceConfiguration.class);

    @Value("${logging.sampling-rate:0.01}")
    private double logSamplingRate;

    @Bean
    CacheService cacheService(
            @Value("${cache.scheme}") String scheme,
            @Value("${cache.host}") String host,
            @Value("${cache.path}") String path,
            @Value("${cache.query}") String query,
            @Value("${cache.banner-ttl-seconds:#{null}}") Integer bannerCacheTtl,
            @Value("${cache.video-ttl-seconds:#{null}}") Integer videoCacheTtl,
            @Value("${auction.cache.expected-request-time-ms}") long expectedCacheTimeMs,
            VastModifier vastModifier,
            EventsService eventsService,
            HttpClient httpClient,
            Metrics metrics,
            Clock clock,
            JacksonMapper mapper) {

        return new CacheService(
                CacheTtl.of(bannerCacheTtl, videoCacheTtl),
                httpClient,
                CacheService.getCacheEndpointUrl(scheme, host, path),
                CacheService.getCachedAssetUrlTemplate(scheme, host, path, query),
                expectedCacheTimeMs,
                vastModifier,
                eventsService,
                metrics,
                clock,
                new UUIDIdGenerator(),
                mapper);
    }

    @Bean
    VastModifier vastModifier(BidderCatalog bidderCatalog, EventsService eventsService, Metrics metrics) {
        return new VastModifier(bidderCatalog, eventsService, metrics);
    }

    @Bean
    @ConditionalOnProperty(prefix = "auction", name = "category-mapping-enabled", havingValue = "true")
    CategoryMappingService basicCategoryMappingService(ApplicationSettings applicationSettings,
                                                       JacksonMapper jacksonMapper) {

        return new BasicCategoryMappingService(applicationSettings, jacksonMapper);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "auction",
            name = "category-mapping-enabled",
            matchIfMissing = true,
            havingValue = "false")
    CategoryMappingService noOpCategoryMappingService() {
        return new NoOpCategoryMappingService();
    }

    @Bean
    ImplicitParametersExtractor implicitParametersExtractor(PublicSuffixList psl) {
        return new ImplicitParametersExtractor(psl);
    }

    @Bean
    IpAddressHelper ipAddressHelper(@Value("${ipv6.always-mask-right}") int ipv6AlwaysMaskBits,
                                    @Value("${ipv6.anon-left-mask-bits}") int ipv6AnonLeftMaskBits,
                                    @Value("${ipv6.private-networks}") String ipv6PrivateNetworksAsString) {

        final List<String> ipv6LocalNetworks = Arrays.asList(ipv6PrivateNetworksAsString.trim().split(","));

        return new IpAddressHelper(ipv6AlwaysMaskBits, ipv6AnonLeftMaskBits, ipv6LocalNetworks);
    }

    @Bean
    FpdResolver fpdResolver(JacksonMapper mapper, JsonMerger jsonMerger) {
        return new FpdResolver(mapper, jsonMerger);
    }

    @Bean
    OrtbTypesResolver ortbTypesResolver(JacksonMapper jacksonMapper, JsonMerger jsonMerger) {
        return new OrtbTypesResolver(logSamplingRate, jacksonMapper, jsonMerger);
    }

    @Bean
    SupplyChainResolver schainResolver(
            @Value("${auction.host-schain-node}") String globalSchainNode,
            JacksonMapper mapper) {

        return SupplyChainResolver.create(globalSchainNode, mapper);
    }

    @Bean
    TimeoutResolver auctionTimeoutResolver(
            @Value("${auction.biddertmax.min}") long minTimeout,
            @Value("${auction.max-timeout-ms:#{0}}") long maxTimeoutDeprecated,
            @Value("${auction.biddertmax.max:#{0}}") long maxTimeout,
            @Value("${auction.tmax-upstream-response-time}") long upstreamResponseTime) {

        return new TimeoutResolver(
                minTimeout,
                resolveMaxTimeout(maxTimeoutDeprecated, maxTimeout),
                upstreamResponseTime);
    }

    // TODO: Remove after transition period
    private static long resolveMaxTimeout(long maxTimeoutDeprecated, long maxTimeout) {
        if (maxTimeout != 0) {
            return maxTimeout;
        }

        logger.warn("Usage of deprecated property: auction.max-timeout-ms. Use auction.biddertmax.max instead.");
        return maxTimeoutDeprecated;
    }

    @Bean
    DebugResolver debugResolver(@Value("${debug.override-token:#{null}}") String debugOverrideToken,
                                BidderCatalog bidderCatalog) {
        return new DebugResolver(bidderCatalog, debugOverrideToken);
    }

    @Bean
    Ortb2ImplicitParametersResolver ortb2ImplicitParametersResolver(
            @Value("${auction.cache.only-winning-bids}") boolean cacheOnlyWinningBids,
            @Value("${settings.generate-storedrequest-bidrequest-id}") boolean generateBidRequestId,
            @Value("${auction.ad-server-currency}") String adServerCurrency,
            @Value("${auction.blacklisted-apps}") String blacklistedAppsString,
            @Value("${external-url}") String externalUrl,
            @Value("${gdpr.host-vendor-id:#{null}}") Integer hostVendorId,
            @Value("${datacenter-region}") String datacenterRegion,
            ImplicitParametersExtractor implicitParametersExtractor,
            TimeoutResolver timeoutResolver,
            IpAddressHelper ipAddressHelper,
            IdGenerator sourceIdGenerator,
            JsonMerger jsonMerger,
            JacksonMapper mapper) {

        return new Ortb2ImplicitParametersResolver(
                cacheOnlyWinningBids,
                generateBidRequestId,
                adServerCurrency,
                splitToList(blacklistedAppsString),
                externalUrl,
                hostVendorId,
                datacenterRegion,
                implicitParametersExtractor,
                timeoutResolver,
                ipAddressHelper,
                sourceIdGenerator,
                jsonMerger,
                mapper);
    }

    @Bean
    BidRequestOrtbVersionConverterFactory bidRequestOrtbVersionConverterFactory(JacksonMapper jacksonMapper) {
        return new BidRequestOrtbVersionConverterFactory(jacksonMapper);
    }

    @Bean
    BidRequestOrtbVersionConversionManager bidRequestOrtbVersionConversionManager(
            BidRequestOrtbVersionConverterFactory bidRequestOrtbVersionConverterFactory) {

        return new BidRequestOrtbVersionConversionManager(bidRequestOrtbVersionConverterFactory);
    }

    @Bean
    Ortb2RequestFactory openRtb2RequestFactory(
            @Value("${settings.enforce-valid-account}") boolean enforceValidAccount,
            @Value("${auction.blacklisted-accounts}") String blacklistedAccountsString,
            UidsCookieService uidsCookieService,
            RequestValidator requestValidator,
            TimeoutResolver auctionTimeoutResolver,
            TimeoutFactory timeoutFactory,
            StoredRequestProcessor storedRequestProcessor,
            ApplicationSettings applicationSettings,
            IpAddressHelper ipAddressHelper,
            HookStageExecutor hookStageExecutor,
            @Autowired(required = false) UserAdditionalInfoService userAdditionalInfoService,
            CountryCodeMapper countryCodeMapper,
            PriceFloorProcessor priceFloorProcessor,
            Metrics metrics,
            Clock clock) {

        final List<String> blacklistedAccounts = splitToList(blacklistedAccountsString);

        return new Ortb2RequestFactory(
                enforceValidAccount,
                logSamplingRate,
                blacklistedAccounts,
                uidsCookieService,
                requestValidator,
                auctionTimeoutResolver,
                timeoutFactory,
                storedRequestProcessor,
                applicationSettings,
                ipAddressHelper,
                hookStageExecutor,
                userAdditionalInfoService,
                priceFloorProcessor,
                countryCodeMapper,
                metrics,
                clock);
    }

    @Bean
    AuctionRequestFactory auctionRequestFactory(
            @Value("${auction.max-request-size}") @Min(0) int maxRequestSize,
            Ortb2RequestFactory ortb2RequestFactory,
            StoredRequestProcessor storedRequestProcessor,
            BidRequestOrtbVersionConversionManager bidRequestOrtbVersionConversionManager,
            ImplicitParametersExtractor implicitParametersExtractor,
            Ortb2ImplicitParametersResolver ortb2ImplicitParametersResolver,
            OrtbTypesResolver ortbTypesResolver,
            PrivacyEnforcementService privacyEnforcementService,
            DebugResolver debugResolver,
            JacksonMapper mapper) {

        return new AuctionRequestFactory(
                maxRequestSize,
                ortb2RequestFactory,
                storedRequestProcessor,
                bidRequestOrtbVersionConversionManager,
                implicitParametersExtractor,
                ortb2ImplicitParametersResolver,
                new InterstitialProcessor(),
                ortbTypesResolver,
                privacyEnforcementService,
                debugResolver,
                mapper);
    }

    @Bean
    BidAdjustmentFactorResolver bidAdjustmentFactorResolver() {
        return new BidAdjustmentFactorResolver();
    }

    @Bean
    IdGenerator bidIdGenerator(@Value("${auction.generate-bid-id}") boolean generateBidId) {
        return generateBidId
                ? new UUIDIdGenerator()
                : new NoneIdGenerator();
    }

    // TODO: Remove this bean creation after deprecation period
    @Bean
    IdGenerator sourceIdGenerator(@Value("${auction.generate-source-tid}") Boolean generateSourceTid) {
        if (generateSourceTid != null) {
            logger.warn("'auction.generate-source-tid' is no longer supported, pls remove from your config");
        }

        return new UUIDIdGenerator();
    }

    @Bean
    AmpRequestFactory ampRequestFactory(Ortb2RequestFactory ortb2RequestFactory,
                                        StoredRequestProcessor storedRequestProcessor,
                                        BidRequestOrtbVersionConversionManager bidRequestOrtbVersionConversionManager,
                                        OrtbTypesResolver ortbTypesResolver,
                                        ImplicitParametersExtractor implicitParametersExtractor,
                                        Ortb2ImplicitParametersResolver ortb2ImplicitParametersResolver,
                                        FpdResolver fpdResolver,
                                        AmpPrivacyContextFactory ampPrivacyContextFactory,
                                        DebugResolver debugResolver,
                                        JacksonMapper mapper) {

        return new AmpRequestFactory(
                ortb2RequestFactory,
                storedRequestProcessor,
                bidRequestOrtbVersionConversionManager,
                ortbTypesResolver,
                implicitParametersExtractor,
                ortb2ImplicitParametersResolver,
                fpdResolver,
                ampPrivacyContextFactory,
                debugResolver,
                mapper);
    }

    @Bean
    VideoRequestFactory videoRequestFactory(
            @Value("${auction.max-request-size}") int maxRequestSize,
            @Value("${video.stored-request-required}") boolean enforceStoredRequest,
            @Value("${auction.video.escape-log-cache-regex:#{null}}") String escapeLogCacheRegex,
            Ortb2RequestFactory ortb2RequestFactory,
            VideoStoredRequestProcessor storedRequestProcessor,
            BidRequestOrtbVersionConversionManager bidRequestOrtbVersionConversionManager,
            Ortb2ImplicitParametersResolver ortb2ImplicitParametersResolver,
            PrivacyEnforcementService privacyEnforcementService,
            DebugResolver debugResolver,
            JacksonMapper mapper) {

        return new VideoRequestFactory(
                maxRequestSize,
                enforceStoredRequest,
                escapeLogCacheRegex,
                ortb2RequestFactory,
                storedRequestProcessor,
                bidRequestOrtbVersionConversionManager,
                ortb2ImplicitParametersResolver,
                privacyEnforcementService,
                debugResolver,
                mapper);
    }

    @Bean
    VideoResponseFactory videoResponseFactory(JacksonMapper mapper) {
        return new VideoResponseFactory(new UUIDIdGenerator(), mapper);
    }

    @Bean
    VideoStoredRequestProcessor videoStoredRequestProcessor(
            @Value("${video.stored-request-required}") boolean enforceStoredRequest,
            @Value("${auction.blacklisted-accounts}") String blacklistedAccountsString,
            @Value("${video.stored-requests-timeout-ms}") long defaultTimeoutMs,
            @Value("${auction.ad-server-currency:#{null}}") String adServerCurrency,
            @Value("${default-request.file.path:#{null}}") String defaultBidRequestPath,
            FileSystem fileSystem,
            ApplicationSettings applicationSettings,
            VideoRequestValidator videoRequestValidator,
            Metrics metrics,
            TimeoutFactory timeoutFactory,
            JacksonMapper mapper,
            JsonMerger jsonMerger) {

        return new VideoStoredRequestProcessor(
                enforceStoredRequest,
                splitToList(blacklistedAccountsString),
                defaultTimeoutMs,
                adServerCurrency,
                defaultBidRequestPath,
                fileSystem,
                applicationSettings,
                videoRequestValidator,
                metrics,
                timeoutFactory,
                mapper,
                jsonMerger);
    }

    @Bean
    VideoRequestValidator videoRequestValidator() {
        return new VideoRequestValidator();
    }

    @Bean
    GoogleRecaptchaVerifier googleRecaptchaVerifier(
            @Value("${recaptcha-url}") String recaptchaUrl,
            @Value("${recaptcha-secret}") String recaptchaSecret,
            HttpClient httpClient,
            JacksonMapper mapper) {

        return new GoogleRecaptchaVerifier(recaptchaUrl, recaptchaSecret, httpClient, mapper);
    }

    @Bean
    @ConfigurationProperties(prefix = "http-client")
    HttpClientProperties httpClientProperties() {
        return new HttpClientProperties();
    }

    @Bean
    @Scope(scopeName = VertxContextScope.NAME, proxyMode = ScopedProxyMode.INTERFACES)
    @ConditionalOnProperty(prefix = "http-client.circuit-breaker", name = "enabled", havingValue = "false",
            matchIfMissing = true)
    BasicHttpClient basicHttpClient(Vertx vertx, HttpClientProperties httpClientProperties) {
        return createBasicHttpClient(vertx, httpClientProperties);
    }

    @Bean
    @ConfigurationProperties(prefix = "http-client.circuit-breaker")
    @ConditionalOnProperty(prefix = "http-client.circuit-breaker", name = "enabled", havingValue = "true")
    HttpClientCircuitBreakerProperties httpClientCircuitBreakerProperties() {
        return new HttpClientCircuitBreakerProperties();
    }

    @Bean
    @Scope(scopeName = VertxContextScope.NAME, proxyMode = ScopedProxyMode.INTERFACES)
    @ConditionalOnProperty(prefix = "http-client.circuit-breaker", name = "enabled", havingValue = "true")
    CircuitBreakerSecuredHttpClient circuitBreakerSecuredHttpClient(
            Vertx vertx,
            Metrics metrics,
            HttpClientProperties httpClientProperties,
            @Qualifier("httpClientCircuitBreakerProperties")
            HttpClientCircuitBreakerProperties circuitBreakerProperties,
            Clock clock) {

        final HttpClient httpClient = createBasicHttpClient(vertx, httpClientProperties);

        return new CircuitBreakerSecuredHttpClient(
                vertx,
                httpClient,
                metrics,
                circuitBreakerProperties.getOpeningThreshold(),
                circuitBreakerProperties.getOpeningIntervalMs(),
                circuitBreakerProperties.getClosingIntervalMs(),
                circuitBreakerProperties.getIdleExpireHours(),
                clock);
    }

    private static BasicHttpClient createBasicHttpClient(Vertx vertx, HttpClientProperties httpClientProperties) {
        final HttpClientOptions options = new HttpClientOptions()
                .setMaxPoolSize(httpClientProperties.getMaxPoolSize())
                .setIdleTimeoutUnit(TimeUnit.MILLISECONDS)
                .setIdleTimeout(httpClientProperties.getIdleTimeoutMs())
                .setPoolCleanerPeriod(httpClientProperties.getPoolCleanerPeriodMs())
                .setTryUseCompression(httpClientProperties.getUseCompression())
                .setConnectTimeout(httpClientProperties.getConnectTimeoutMs())
                // Vert.x's HttpClientRequest needs this value to be 2 for redirections to be followed once,
                // 3 for twice, and so on
                .setMaxRedirects(httpClientProperties.getMaxRedirects() + 1);

        if (httpClientProperties.getSsl()) {
            final JksOptions jksOptions = new JksOptions()
                    .setPath(httpClientProperties.getJksPath())
                    .setPassword(httpClientProperties.getJksPassword());

            options
                    .setSsl(true)
                    .setKeyStoreOptions(jksOptions);
        }

        return new BasicHttpClient(vertx, vertx.createHttpClient(options));
    }

    @Bean
    PrioritizedCoopSyncProvider prioritizedCoopSyncProvider(
            @Value("${cookie-sync.pri:#{null}}") String prioritizedBidders,
            BidderCatalog bidderCatalog) {

        return new PrioritizedCoopSyncProvider(splitToSet(prioritizedBidders), bidderCatalog);
    }

    @Bean
    UidsCookieService uidsCookieService(
            @Value("${host-cookie.optout-cookie.name:#{null}}") String optOutCookieName,
            @Value("${host-cookie.optout-cookie.value:#{null}}") String optOutCookieValue,
            @Value("${host-cookie.family:#{null}}") String hostCookieFamily,
            @Value("${host-cookie.cookie-name:#{null}}") String hostCookieName,
            @Value("${host-cookie.domain:#{null}}") String hostCookieDomain,
            @Value("${host-cookie.ttl-days}") Integer ttlDays,
            @Value("${host-cookie.max-cookie-size-bytes}") Integer maxCookieSizeBytes,
            PrioritizedCoopSyncProvider prioritizedCoopSyncProvider,
            Metrics metrics,
            JacksonMapper mapper) {

        return new UidsCookieService(
                optOutCookieName,
                optOutCookieValue,
                hostCookieFamily,
                hostCookieName,
                hostCookieDomain,
                ttlDays,
                maxCookieSizeBytes,
                prioritizedCoopSyncProvider,
                metrics,
                mapper);
    }

    @Bean
    UidUpdater uidUpdater(
            @Value("${host-cookie.family:#{null}}") String hostCookieFamily,
            BidderCatalog bidderCatalog,
            UidsCookieService uidsCookieService) {

        return new UidUpdater(hostCookieFamily, bidderCatalog, uidsCookieService);
    }

    @Bean
    CoopSyncProvider coopSyncProvider(
            BidderCatalog bidderCatalog,
            PrioritizedCoopSyncProvider prioritizedCoopSyncProvider,
            @Value("${cookie-sync.coop-sync.default:false}") boolean defaultCoopSync) {

        return new CoopSyncProvider(bidderCatalog, prioritizedCoopSyncProvider, defaultCoopSync);
    }

    @Bean
    CookieSyncService cookieSyncService(
            @Value("${external-url}") String externalUrl,
            @Value("${cookie-sync.default-limit:#{2}}") Integer defaultLimit,
            @Value("${cookie-sync.max-limit:#{null}}") Integer maxLimit,
            BidderCatalog bidderCatalog,
            HostVendorTcfDefinerService hostVendorTcfDefinerService,
            PrivacyEnforcementService privacyEnforcementService,
            UidsCookieService uidsCookieService,
            CoopSyncProvider coopSyncProvider,
            Metrics metrics) {

        return new CookieSyncService(
                externalUrl,
                defaultLimit,
                ObjectUtils.defaultIfNull(maxLimit, Integer.MAX_VALUE),
                bidderCatalog,
                hostVendorTcfDefinerService,
                privacyEnforcementService,
                uidsCookieService,
                coopSyncProvider,
                metrics);
    }

    @Bean
    EventsService eventsService(@Value("${external-url}") String externalUrl) {
        return new EventsService(externalUrl);
    }

    @Bean
    BidderCatalog bidderCatalog(List<BidderDeps> bidderDeps) {
        return new BidderCatalog(bidderDeps);
    }

    @Bean
    @ConditionalOnProperty(prefix = "auction.filter-imp-media-type", name = "enabled", havingValue = "true")
    MediaTypeProcessor bidderMediaTypeProcessor(BidderCatalog bidderCatalog) {
        return new BidderMediaTypeProcessor(bidderCatalog);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "auction.filter-imp-media-type",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true)
    MediaTypeProcessor noOpMediaTypeProcessor() {
        return new NoOpMediaTypeProcessor();
    }

    @Bean
    HttpBidderRequester httpBidderRequester(
            HttpClient httpClient,
            @Autowired(required = false) BidderRequestCompletionTrackerFactory bidderRequestCompletionTrackerFactory,
            BidderErrorNotifier bidderErrorNotifier,
            HttpBidderRequestEnricher requestEnricher,
            JacksonMapper mapper) {

        return new HttpBidderRequester(httpClient,
                bidderRequestCompletionTrackerFactory,
                bidderErrorNotifier,
                requestEnricher,
                mapper);
    }

    @Bean
    PrebidVersionProvider prebidVersionProvider(VersionInfo versionInfo) {
        return new PrebidVersionProvider(versionInfo.getVersion());
    }

    @Bean
    HttpBidderRequestEnricher httpBidderRequestEnricher(PrebidVersionProvider prebidVersionProvider,
                                                        BidderCatalog bidderCatalog) {

        return new HttpBidderRequestEnricher(prebidVersionProvider, bidderCatalog);
    }

    @Bean
    BidderErrorNotifier bidderErrorNotifier(
            @Value("${auction.timeout-notification.timeout-ms}") int timeoutNotificationTimeoutMs,
            @Value("${auction.timeout-notification.log-result}") boolean logTimeoutNotificationResult,
            @Value("${auction.timeout-notification.log-failure-only}") boolean logTimeoutNotificationFailureOnly,
            @Value("${auction.timeout-notification.log-sampling-rate}") double logTimeoutNotificationSamplingRate,
            HttpClient httpClient,
            Metrics metrics) {

        return new BidderErrorNotifier(
                timeoutNotificationTimeoutMs,
                logTimeoutNotificationResult,
                logTimeoutNotificationFailureOnly,
                logTimeoutNotificationSamplingRate,
                httpClient,
                metrics);
    }

    @Bean
    BidResponseCreator bidResponseCreator(
            CacheService cacheService,
            BidderCatalog bidderCatalog,
            VastModifier vastModifier,
            EventsService eventsService,
            StoredRequestProcessor storedRequestProcessor,
            WinningBidComparatorFactory winningBidComparatorFactory,
            IdGenerator bidIdGenerator,
            HookStageExecutor hookStageExecutor,
            CategoryMappingService categoryMappingService,
            @Value("${settings.targeting.truncate-attr-chars}") int truncateAttrChars,
            Clock clock,
            JacksonMapper mapper) {

        return new BidResponseCreator(
                cacheService,
                bidderCatalog,
                vastModifier,
                eventsService,
                storedRequestProcessor,
                winningBidComparatorFactory,
                bidIdGenerator,
                hookStageExecutor,
                categoryMappingService,
                truncateAttrChars,
                clock,
                mapper);
    }

    @Bean
    ExchangeService exchangeService(
            @Value("${auction.biddertmax.percent}") int timeoutAdjustmentFactor,
            BidderCatalog bidderCatalog,
            StoredResponseProcessor storedResponseProcessor,
            @Autowired(required = false) DealsService dealsService,
            PrivacyEnforcementService privacyEnforcementService,
            FpdResolver fpdResolver,
            SupplyChainResolver supplyChainResolver,
            DebugResolver debugResolver,
            MediaTypeProcessor mediaTypeProcessor,
            UidUpdater uidUpdater,
            TimeoutResolver timeoutResolver,
            TimeoutFactory timeoutFactory,
            BidRequestOrtbVersionConversionManager bidRequestOrtbVersionConversionManager,
            HttpBidderRequester httpBidderRequester,
            ResponseBidValidator responseBidValidator,
            CurrencyConversionService currencyConversionService,
            BidResponseCreator bidResponseCreator,
            BidResponsePostProcessor bidResponsePostProcessor,
            HookStageExecutor hookStageExecutor,
            @Autowired(required = false) ApplicationEventService applicationEventService,
            HttpInteractionLogger httpInteractionLogger,
            PriceFloorAdjuster priceFloorAdjuster,
            PriceFloorEnforcer priceFloorEnforcer,
            BidAdjustmentFactorResolver bidAdjustmentFactorResolver,
            Metrics metrics,
            Clock clock,
            JacksonMapper mapper,
            CriteriaLogManager criteriaLogManager) {

        return new ExchangeService(
                timeoutAdjustmentFactor,
                bidderCatalog,
                storedResponseProcessor,
                dealsService,
                privacyEnforcementService,
                fpdResolver,
                supplyChainResolver,
                debugResolver,
                mediaTypeProcessor,
                uidUpdater,
                timeoutResolver,
                timeoutFactory,
                bidRequestOrtbVersionConversionManager,
                httpBidderRequester,
                responseBidValidator,
                currencyConversionService,
                bidResponseCreator,
                bidResponsePostProcessor,
                hookStageExecutor,
                applicationEventService,
                httpInteractionLogger,
                priceFloorAdjuster,
                priceFloorEnforcer,
                bidAdjustmentFactorResolver,
                metrics,
                clock,
                mapper,
                criteriaLogManager);
    }

    @Bean
    StoredRequestProcessor storedRequestProcessor(
            @Value("${auction.stored-requests-timeout-ms}") long defaultTimeoutMs,
            @Value("${default-request.file.path:#{null}}") String defaultBidRequestPath,
            @Value("${settings.generate-storedrequest-bidrequest-id}") boolean generateBidRequestId,
            FileSystem fileSystem,
            ApplicationSettings applicationSettings,
            Metrics metrics,
            TimeoutFactory timeoutFactory,
            JacksonMapper mapper,
            JsonMerger jsonMerger) {

        return new StoredRequestProcessor(
                defaultTimeoutMs,
                defaultBidRequestPath,
                generateBidRequestId,
                fileSystem,
                applicationSettings,
                new UUIDIdGenerator(),
                metrics,
                timeoutFactory,
                mapper,
                jsonMerger);
    }

    @Bean
    WinningBidComparatorFactory winningBidComparatorFactory() {
        return new WinningBidComparatorFactory();
    }

    @Bean
    StoredResponseProcessor storedResponseProcessor(ApplicationSettings applicationSettings,
                                                    JacksonMapper mapper) {

        return new StoredResponseProcessor(applicationSettings, mapper);
    }

    @Bean
    PrivacyEnforcementService privacyEnforcementService(
            BidderCatalog bidderCatalog,
            PrivacyExtractor privacyExtractor,
            TcfDefinerService tcfDefinerService,
            ImplicitParametersExtractor implicitParametersExtractor,
            IpAddressHelper ipAddressHelper,
            Metrics metrics,
            CountryCodeMapper countryCodeMapper,
            @Value("${ccpa.enforce}") boolean ccpaEnforce,
            @Value("${lmt.enforce}") boolean lmtEnforce) {

        return new PrivacyEnforcementService(
                bidderCatalog,
                privacyExtractor,
                tcfDefinerService,
                implicitParametersExtractor,
                ipAddressHelper,
                metrics,
                countryCodeMapper,
                ccpaEnforce,
                lmtEnforce);
    }

    @Bean
    AmpPrivacyContextFactory ampPrivacyContextFactory(PrivacyExtractor privacyExtractor,
                                                      TcfDefinerService tcfDefinerService,
                                                      IpAddressHelper ipAddressHelper,
                                                      CountryCodeMapper countryCodeMapper) {

        return new AmpPrivacyContextFactory(
                privacyExtractor,
                tcfDefinerService,
                ipAddressHelper,
                countryCodeMapper);
    }

    @Bean
    PrivacyExtractor privacyExtractor() {
        return new PrivacyExtractor();
    }

    @Bean
    VersionInfo versionInfo(JacksonMapper jacksonMapper) {
        return VersionInfo.create("git-revision.json", jacksonMapper);
    }

    @Bean
    RequestValidator requestValidator(BidderCatalog bidderCatalog,
                                      BidderParamValidator bidderParamValidator,
                                      JacksonMapper mapper) {

        return new RequestValidator(bidderCatalog, bidderParamValidator, mapper);
    }

    @Bean
    PriceFloorsConfigResolver accountValidator(
            @Value("${settings.default-account-config:#{null}}") String defaultAccountConfig,
            Metrics metrics,
            JacksonMapper jacksonMapper) {

        return new PriceFloorsConfigResolver(defaultAccountConfig, metrics, jacksonMapper);
    }

    @Bean
    BidderParamValidator bidderParamValidator(BidderCatalog bidderCatalog, JacksonMapper mapper) {
        return BidderParamValidator.create(bidderCatalog, "static/bidder-params", mapper);
    }

    @Bean
    ResponseBidValidator responseValidator(
            @Value("${auction.validations.banner-creative-max-size}") BidValidationEnforcement bannerMaxSizeEnforcement,
            @Value("${auction.validations.secure-markup}") BidValidationEnforcement secureMarkupEnforcement,
            Metrics metrics,
            JacksonMapper mapper,
            @Value("${deals.enabled}") boolean dealsEnabled) {

        return new ResponseBidValidator(
                bannerMaxSizeEnforcement,
                secureMarkupEnforcement,
                metrics,
                mapper,
                dealsEnabled,
                logSamplingRate);
    }

    @Bean
    CriteriaLogManager criteriaLogManager(JacksonMapper mapper) {
        return new CriteriaLogManager(mapper);
    }

    @Bean
    CriteriaManager criteriaManager(CriteriaLogManager criteriaLogManager, Vertx vertx) {
        return new CriteriaManager(criteriaLogManager, vertx);
    }

    @Bean
    PublicSuffixList psl() {
        final PublicSuffixListFactory factory = new PublicSuffixListFactory();

        final Properties properties = factory.getDefaults();
        properties.setProperty(PublicSuffixListFactory.PROPERTY_LIST_FILE, "/effective_tld_names.dat");
        try {
            return factory.build(properties);
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalArgumentException("Could not initialize public suffix list", e);
        }
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TimeoutFactory timeoutFactory(Clock clock) {
        return new TimeoutFactory(clock);
    }

    @Bean
    BidResponsePostProcessor bidResponsePostProcessor() {
        return BidResponsePostProcessor.noOp();
    }

    @Bean
    AmpResponsePostProcessor ampResponsePostProcessor() {
        return AmpResponsePostProcessor.noOp();
    }

    @Bean
    CurrencyConversionService currencyConversionService(
            @Autowired(required = false) ExternalConversionProperties externalConversionProperties) {

        return new CurrencyConversionService(externalConversionProperties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "currency-converter.external-rates", name = "enabled", havingValue = "true")
    ExternalConversionProperties externalConversionProperties(
            @Value("${currency-converter.external-rates.url}") String currencyServerUrl,
            @Value("${currency-converter.external-rates.default-timeout-ms}") long defaultTimeoutMs,
            @Value("${currency-converter.external-rates.refresh-period-ms}") long refreshPeriodMs,
            @Value("${currency-converter.external-rates.stale-after-ms}") long staleAfterMs,
            @Value("${currency-converter.external-rates.stale-period-ms:#{null}}") Long stalePeriodMs,
            Vertx vertx,
            HttpClient httpClient,
            Metrics metrics,
            Clock clock,
            JacksonMapper mapper) {

        return new ExternalConversionProperties(
                currencyServerUrl,
                defaultTimeoutMs,
                refreshPeriodMs,
                staleAfterMs,
                stalePeriodMs,
                vertx,
                httpClient,
                metrics,
                clock,
                mapper);
    }

    @Bean
    HttpInteractionLogger httpInteractionLogger(JacksonMapper mapper) {
        return new HttpInteractionLogger(mapper);
    }

    @Bean
    LoggerControlKnob loggerControlKnob(Vertx vertx) {
        return new LoggerControlKnob(vertx);
    }

    private static List<String> splitToList(String listAsString) {
        return splitToCollection(listAsString, ArrayList::new);
    }

    private static Set<String> splitToSet(String listAsString) {
        return splitToCollection(listAsString, HashSet::new);
    }

    private static <T extends Collection<String>> T splitToCollection(String listAsString,
                                                                      Supplier<T> collectionFactory) {

        return listAsString != null
                ? Stream.of(listAsString.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(collectionFactory))
                : collectionFactory.get();
    }
}
