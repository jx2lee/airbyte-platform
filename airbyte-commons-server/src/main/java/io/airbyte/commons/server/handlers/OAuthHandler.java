/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.server.handlers;

import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.DESTINATION_DEFINITION_ID_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.SOURCE_DEFINITION_ID_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.WORKSPACE_ID_KEY;

import com.amazonaws.util.json.Jackson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import io.airbyte.analytics.TrackingClient;
import io.airbyte.api.model.generated.CompleteDestinationOAuthRequest;
import io.airbyte.api.model.generated.CompleteOAuthResponse;
import io.airbyte.api.model.generated.CompleteSourceOauthRequest;
import io.airbyte.api.model.generated.DestinationOauthConsentRequest;
import io.airbyte.api.model.generated.OAuthConsentRead;
import io.airbyte.api.model.generated.SetInstancewideDestinationOauthParamsRequestBody;
import io.airbyte.api.model.generated.SetInstancewideSourceOauthParamsRequestBody;
import io.airbyte.api.model.generated.SourceOauthConsentRequest;
import io.airbyte.commons.constants.AirbyteSecretConstants;
import io.airbyte.commons.json.JsonPaths;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.server.handlers.helpers.OAuthPathExtractor;
import io.airbyte.config.ActorDefinitionVersion;
import io.airbyte.config.DestinationConnection;
import io.airbyte.config.DestinationOAuthParameter;
import io.airbyte.config.SourceConnection;
import io.airbyte.config.SourceOAuthParameter;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.persistence.ActorDefinitionVersionHelper;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.config.persistence.SecretsRepositoryReader;
import io.airbyte.config.persistence.SecretsRepositoryWriter;
import io.airbyte.config.persistence.split_secrets.SecretCoordinate;
import io.airbyte.config.persistence.split_secrets.SecretsHelpers;
import io.airbyte.metrics.lib.ApmTraceUtils;
import io.airbyte.oauth.OAuthFlowImplementation;
import io.airbyte.oauth.OAuthImplementationFactory;
import io.airbyte.persistence.job.factory.OAuthConfigSupplier;
import io.airbyte.persistence.job.tracker.TrackingMetadata;
import io.airbyte.protocol.models.ConnectorSpecification;
import io.airbyte.validation.json.JsonValidationException;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuthHandler. Javadocs suppressed because api docs should be used as source of truth.
 */
@SuppressWarnings({"MissingJavadocMethod", "ParameterName"})
@Singleton
public class OAuthHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(OAuthHandler.class);
  private static final String ERROR_MESSAGE = "failed while reporting usage.";

  private final ConfigRepository configRepository;
  private final OAuthImplementationFactory oAuthImplementationFactory;
  private final TrackingClient trackingClient;
  private final SecretsRepositoryReader secretsRepositoryReader;
  private final SecretsRepositoryWriter secretsRepositoryWriter;
  private final ActorDefinitionVersionHelper actorDefinitionVersionHelper;

  public OAuthHandler(final ConfigRepository configRepository,
                      @Named("oauthHttpClient") final HttpClient httpClient,
                      final TrackingClient trackingClient,
                      final SecretsRepositoryReader secretsRepositoryReader,
                      final SecretsRepositoryWriter secretsRepositoryWriter,
                      final ActorDefinitionVersionHelper actorDefinitionVersionHelper) {
    this.configRepository = configRepository;
    this.oAuthImplementationFactory = new OAuthImplementationFactory(configRepository, httpClient);
    this.trackingClient = trackingClient;
    this.secretsRepositoryReader = secretsRepositoryReader;
    this.secretsRepositoryWriter = secretsRepositoryWriter;
    this.actorDefinitionVersionHelper = actorDefinitionVersionHelper;
  }

  public OAuthConsentRead getSourceOAuthConsent(final SourceOauthConsentRequest sourceOauthConsentRequest)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final Map<String, Object> traceTags = Map.of(WORKSPACE_ID_KEY, sourceOauthConsentRequest.getWorkspaceId(), SOURCE_DEFINITION_ID_KEY,
        sourceOauthConsentRequest.getSourceDefinitionId());
    ApmTraceUtils.addTagsToTrace(traceTags);
    ApmTraceUtils.addTagsToRootSpan(traceTags);
    final StandardSourceDefinition sourceDefinition =
        configRepository.getStandardSourceDefinition(sourceOauthConsentRequest.getSourceDefinitionId());
    final ActorDefinitionVersion sourceVersion = sourceOauthConsentRequest.getSourceId() != null
        ? actorDefinitionVersionHelper.getSourceVersion(sourceDefinition, sourceOauthConsentRequest.getSourceId())
        : actorDefinitionVersionHelper.getSourceVersionForWorkspace(sourceDefinition, sourceOauthConsentRequest.getWorkspaceId());
    final OAuthFlowImplementation oAuthFlowImplementation = oAuthImplementationFactory.create(sourceVersion.getDockerRepository());
    final ConnectorSpecification spec = sourceVersion.getSpec();
    final Map<String, Object> metadata = generateSourceMetadata(sourceOauthConsentRequest.getSourceDefinitionId());
    final OAuthConsentRead result;
    if (OAuthConfigSupplier.hasOAuthConfigSpecification(spec)) {
      final JsonNode oAuthInputConfigurationForConsent;

      if (sourceOauthConsentRequest.getSourceId() == null) {
        oAuthInputConfigurationForConsent = sourceOauthConsentRequest.getoAuthInputConfiguration();
      } else {
        final SourceConnection hydratedSourceConnection =
            secretsRepositoryReader.getSourceConnectionWithSecrets(sourceOauthConsentRequest.getSourceId());

        oAuthInputConfigurationForConsent = getOAuthInputConfigurationForConsent(spec,
            hydratedSourceConnection.getConfiguration(),
            sourceOauthConsentRequest.getoAuthInputConfiguration());
      }

      result = new OAuthConsentRead().consentUrl(oAuthFlowImplementation.getSourceConsentUrl(
          sourceOauthConsentRequest.getWorkspaceId(),
          sourceOauthConsentRequest.getSourceDefinitionId(),
          sourceOauthConsentRequest.getRedirectUrl(),
          oAuthInputConfigurationForConsent,
          spec.getAdvancedAuth().getOauthConfigSpecification()));
    } else {
      result = new OAuthConsentRead().consentUrl(oAuthFlowImplementation.getSourceConsentUrl(
          sourceOauthConsentRequest.getWorkspaceId(),
          sourceOauthConsentRequest.getSourceDefinitionId(),
          sourceOauthConsentRequest.getRedirectUrl(), Jsons.emptyObject(), null));
    }
    try {
      trackingClient.track(sourceOauthConsentRequest.getWorkspaceId(), "Get Oauth Consent URL - Backend", metadata);
    } catch (final Exception e) {
      LOGGER.error(ERROR_MESSAGE, e);
    }
    return result;
  }

  public OAuthConsentRead getDestinationOAuthConsent(final DestinationOauthConsentRequest destinationOauthConsentRequest)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final Map<String, Object> traceTags = Map.of(WORKSPACE_ID_KEY, destinationOauthConsentRequest.getWorkspaceId(), DESTINATION_DEFINITION_ID_KEY,
        destinationOauthConsentRequest.getDestinationDefinitionId());
    ApmTraceUtils.addTagsToTrace(traceTags);
    ApmTraceUtils.addTagsToRootSpan(traceTags);

    final StandardDestinationDefinition destinationDefinition =
        configRepository.getStandardDestinationDefinition(destinationOauthConsentRequest.getDestinationDefinitionId());
    final ActorDefinitionVersion destinationVersion = destinationOauthConsentRequest.getDestinationId() != null
        ? actorDefinitionVersionHelper.getDestinationVersion(destinationDefinition, destinationOauthConsentRequest.getDestinationId())
        : actorDefinitionVersionHelper.getDestinationVersionForWorkspace(destinationDefinition, destinationOauthConsentRequest.getWorkspaceId());
    final OAuthFlowImplementation oAuthFlowImplementation = oAuthImplementationFactory.create(destinationVersion.getDockerRepository());
    final ConnectorSpecification spec = destinationVersion.getSpec();
    final Map<String, Object> metadata = generateDestinationMetadata(destinationOauthConsentRequest.getDestinationDefinitionId());
    final OAuthConsentRead result;
    if (OAuthConfigSupplier.hasOAuthConfigSpecification(spec)) {
      final JsonNode oAuthInputConfigurationForConsent;

      if (destinationOauthConsentRequest.getDestinationId() == null) {
        oAuthInputConfigurationForConsent = destinationOauthConsentRequest.getoAuthInputConfiguration();
      } else {
        final DestinationConnection hydratedSourceConnection =
            secretsRepositoryReader.getDestinationConnectionWithSecrets(destinationOauthConsentRequest.getDestinationId());

        oAuthInputConfigurationForConsent = getOAuthInputConfigurationForConsent(spec,
            hydratedSourceConnection.getConfiguration(),
            destinationOauthConsentRequest.getoAuthInputConfiguration());

      }

      result = new OAuthConsentRead().consentUrl(oAuthFlowImplementation.getDestinationConsentUrl(
          destinationOauthConsentRequest.getWorkspaceId(),
          destinationOauthConsentRequest.getDestinationDefinitionId(),
          destinationOauthConsentRequest.getRedirectUrl(),
          oAuthInputConfigurationForConsent,
          spec.getAdvancedAuth().getOauthConfigSpecification()));
    } else {
      result = new OAuthConsentRead().consentUrl(oAuthFlowImplementation.getDestinationConsentUrl(
          destinationOauthConsentRequest.getWorkspaceId(),
          destinationOauthConsentRequest.getDestinationDefinitionId(),
          destinationOauthConsentRequest.getRedirectUrl(), Jsons.emptyObject(), null));
    }
    try {
      trackingClient.track(destinationOauthConsentRequest.getWorkspaceId(), "Get Oauth Consent URL - Backend", metadata);
    } catch (final Exception e) {
      LOGGER.error(ERROR_MESSAGE, e);
    }
    return result;
  }

  public CompleteOAuthResponse completeSourceOAuthHandleReturnSecret(final CompleteSourceOauthRequest completeSourceOauthRequest)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final CompleteOAuthResponse oAuthTokens = completeSourceOAuth(completeSourceOauthRequest);
    if (oAuthTokens != null && completeSourceOauthRequest.getReturnSecretCoordinate()) {
      return writeOAuthResponseSecret(completeSourceOauthRequest.getWorkspaceId(), oAuthTokens);
    } else {
      return oAuthTokens;
    }
  }

  @VisibleForTesting
  public CompleteOAuthResponse completeSourceOAuth(final CompleteSourceOauthRequest completeSourceOauthRequest)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final Map<String, Object> traceTags = Map.of(WORKSPACE_ID_KEY, completeSourceOauthRequest.getWorkspaceId(), SOURCE_DEFINITION_ID_KEY,
        completeSourceOauthRequest.getSourceDefinitionId());
    ApmTraceUtils.addTagsToTrace(traceTags);
    ApmTraceUtils.addTagsToRootSpan(traceTags);

    final StandardSourceDefinition sourceDefinition =
        configRepository.getStandardSourceDefinition(completeSourceOauthRequest.getSourceDefinitionId());
    final ActorDefinitionVersion sourceVersion = completeSourceOauthRequest.getSourceId() != null
        ? actorDefinitionVersionHelper.getSourceVersion(sourceDefinition, completeSourceOauthRequest.getSourceId())
        : actorDefinitionVersionHelper.getSourceVersionForWorkspace(sourceDefinition, completeSourceOauthRequest.getWorkspaceId());
    final OAuthFlowImplementation oAuthFlowImplementation = oAuthImplementationFactory.create(sourceVersion.getDockerRepository());
    final ConnectorSpecification spec = sourceVersion.getSpec();
    final Map<String, Object> metadata = generateSourceMetadata(completeSourceOauthRequest.getSourceDefinitionId());
    final Map<String, Object> result;
    if (OAuthConfigSupplier.hasOAuthConfigSpecification(spec)) {
      final JsonNode oAuthInputConfigurationForConsent;

      if (completeSourceOauthRequest.getSourceId() == null) {
        oAuthInputConfigurationForConsent = completeSourceOauthRequest.getoAuthInputConfiguration();
      } else {
        final SourceConnection hydratedSourceConnection =
            secretsRepositoryReader.getSourceConnectionWithSecrets(completeSourceOauthRequest.getSourceId());

        oAuthInputConfigurationForConsent = getOAuthInputConfigurationForConsent(spec,
            hydratedSourceConnection.getConfiguration(),
            completeSourceOauthRequest.getoAuthInputConfiguration());
      }

      result = oAuthFlowImplementation.completeSourceOAuth(
          completeSourceOauthRequest.getWorkspaceId(),
          completeSourceOauthRequest.getSourceDefinitionId(),
          completeSourceOauthRequest.getQueryParams(),
          completeSourceOauthRequest.getRedirectUrl(),
          oAuthInputConfigurationForConsent,
          spec.getAdvancedAuth().getOauthConfigSpecification());
    } else {
      // deprecated but this path is kept for connectors that don't define OAuth Spec yet
      result = oAuthFlowImplementation.completeSourceOAuth(
          completeSourceOauthRequest.getWorkspaceId(),
          completeSourceOauthRequest.getSourceDefinitionId(),
          completeSourceOauthRequest.getQueryParams(),
          completeSourceOauthRequest.getRedirectUrl());
    }
    try {
      trackingClient.track(completeSourceOauthRequest.getWorkspaceId(), "Complete OAuth Flow - Backend", metadata);
    } catch (final Exception e) {
      LOGGER.error(ERROR_MESSAGE, e);
    }
    return mapToCompleteOAuthResponse(result);
  }

  public CompleteOAuthResponse completeDestinationOAuth(final CompleteDestinationOAuthRequest completeDestinationOAuthRequest)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final Map<String, Object> traceTags = Map.of(WORKSPACE_ID_KEY, completeDestinationOAuthRequest.getWorkspaceId(), DESTINATION_DEFINITION_ID_KEY,
        completeDestinationOAuthRequest.getDestinationDefinitionId());
    ApmTraceUtils.addTagsToTrace(traceTags);
    ApmTraceUtils.addTagsToRootSpan(traceTags);

    final StandardDestinationDefinition destinationDefinition =
        configRepository.getStandardDestinationDefinition(completeDestinationOAuthRequest.getDestinationDefinitionId());
    final ActorDefinitionVersion destinationVersion = completeDestinationOAuthRequest.getDestinationId() != null
        ? actorDefinitionVersionHelper.getDestinationVersion(destinationDefinition, completeDestinationOAuthRequest.getDestinationId())
        : actorDefinitionVersionHelper.getDestinationVersionForWorkspace(destinationDefinition, completeDestinationOAuthRequest.getWorkspaceId());
    final OAuthFlowImplementation oAuthFlowImplementation = oAuthImplementationFactory.create(destinationVersion.getDockerRepository());
    final ConnectorSpecification spec = destinationVersion.getSpec();
    final Map<String, Object> metadata = generateDestinationMetadata(completeDestinationOAuthRequest.getDestinationDefinitionId());
    final Map<String, Object> result;
    if (OAuthConfigSupplier.hasOAuthConfigSpecification(spec)) {
      final JsonNode oAuthInputConfigurationForConsent;

      if (completeDestinationOAuthRequest.getDestinationId() == null) {
        oAuthInputConfigurationForConsent = completeDestinationOAuthRequest.getoAuthInputConfiguration();
      } else {
        final DestinationConnection hydratedSourceConnection =
            secretsRepositoryReader.getDestinationConnectionWithSecrets(completeDestinationOAuthRequest.getDestinationId());

        oAuthInputConfigurationForConsent = getOAuthInputConfigurationForConsent(spec,
            hydratedSourceConnection.getConfiguration(),
            completeDestinationOAuthRequest.getoAuthInputConfiguration());

      }

      result = oAuthFlowImplementation.completeDestinationOAuth(
          completeDestinationOAuthRequest.getWorkspaceId(),
          completeDestinationOAuthRequest.getDestinationDefinitionId(),
          completeDestinationOAuthRequest.getQueryParams(),
          completeDestinationOAuthRequest.getRedirectUrl(),
          oAuthInputConfigurationForConsent,
          spec.getAdvancedAuth().getOauthConfigSpecification());
    } else {
      // deprecated but this path is kept for connectors that don't define OAuth Spec yet
      result = oAuthFlowImplementation.completeDestinationOAuth(
          completeDestinationOAuthRequest.getWorkspaceId(),
          completeDestinationOAuthRequest.getDestinationDefinitionId(),
          completeDestinationOAuthRequest.getQueryParams(),
          completeDestinationOAuthRequest.getRedirectUrl());
    }
    try {
      trackingClient.track(completeDestinationOAuthRequest.getWorkspaceId(), "Complete OAuth Flow - Backend", metadata);
    } catch (final Exception e) {
      LOGGER.error(ERROR_MESSAGE, e);
    }
    return mapToCompleteOAuthResponse(result);
  }

  public void setSourceInstancewideOauthParams(final SetInstancewideSourceOauthParamsRequestBody requestBody)
      throws JsonValidationException, IOException {
    final SourceOAuthParameter param = configRepository
        .getSourceOAuthParamByDefinitionIdOptional(null, requestBody.getSourceDefinitionId())
        .orElseGet(() -> new SourceOAuthParameter().withOauthParameterId(UUID.randomUUID()))
        .withConfiguration(Jsons.jsonNode(requestBody.getParams()))
        .withSourceDefinitionId(requestBody.getSourceDefinitionId());
    // TODO validate requestBody.getParams() against
    // spec.getAdvancedAuth().getOauthConfigSpecification().getCompleteOauthServerInputSpecification()
    configRepository.writeSourceOAuthParam(param);
  }

  public void setDestinationInstancewideOauthParams(final SetInstancewideDestinationOauthParamsRequestBody requestBody)
      throws JsonValidationException, IOException {
    final DestinationOAuthParameter param = configRepository
        .getDestinationOAuthParamByDefinitionIdOptional(null, requestBody.getDestinationDefinitionId())
        .orElseGet(() -> new DestinationOAuthParameter().withOauthParameterId(UUID.randomUUID()))
        .withConfiguration(Jsons.jsonNode(requestBody.getParams()))
        .withDestinationDefinitionId(requestBody.getDestinationDefinitionId());
    // TODO validate requestBody.getParams() against
    // spec.getAdvancedAuth().getOauthConfigSpecification().getCompleteOauthServerInputSpecification()
    configRepository.writeDestinationOAuthParam(param);
  }

  private JsonNode getOAuthInputConfigurationForConsent(final ConnectorSpecification spec,
                                                        final JsonNode hydratedSourceConnectionConfiguration,
                                                        final JsonNode oAuthInputConfiguration) {
    final Map<String, String> fieldsToGet =
        buildJsonPathFromOAuthFlowInitParameters(OAuthPathExtractor.extractOauthConfigurationPaths(
            spec.getAdvancedAuth().getOauthConfigSpecification().getOauthUserInputFromConnectorConfigSpecification()));

    final JsonNode oAuthInputConfigurationFromDB = getOAuthInputConfiguration(hydratedSourceConnectionConfiguration, fieldsToGet);

    return getOauthFromDBIfNeeded(oAuthInputConfigurationFromDB, oAuthInputConfiguration);
  }

  private Map<String, Object> generateSourceMetadata(final UUID sourceDefinitionId)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final StandardSourceDefinition sourceDefinition = configRepository.getStandardSourceDefinition(sourceDefinitionId);
    return TrackingMetadata.generateSourceDefinitionMetadata(sourceDefinition);
  }

  private Map<String, Object> generateDestinationMetadata(final UUID destinationDefinitionId)
      throws JsonValidationException, ConfigNotFoundException, IOException {
    final StandardDestinationDefinition destinationDefinition = configRepository.getStandardDestinationDefinition(destinationDefinitionId);
    return TrackingMetadata.generateDestinationDefinitionMetadata(destinationDefinition);
  }

  CompleteOAuthResponse mapToCompleteOAuthResponse(Map<String, Object> input) {
    final CompleteOAuthResponse response = new CompleteOAuthResponse();
    response.setAuthPayload(new HashMap<>());

    if (input.containsKey("request_succeeded")) {
      response.setRequestSucceeded("true".equals(input.get("request_succeeded")));
    } else {
      response.setRequestSucceeded(true);
    }

    if (input.containsKey("request_error")) {
      response.setRequestError(input.get("request_error").toString());
    }

    input.forEach((k, v) -> {
      if (k != "request_succeeded" && k != "request_error") {
        response.getAuthPayload().put(k, v);
      }
    });

    return response;
  }

  @VisibleForTesting
  Map<String, String> buildJsonPathFromOAuthFlowInitParameters(final Map<String, List<String>> oAuthFlowInitParameters) {
    return oAuthFlowInitParameters.entrySet().stream()
        .map(entry -> Map.entry(entry.getKey(), "$." + String.join(".", entry.getValue())))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  @VisibleForTesting
  JsonNode getOauthFromDBIfNeeded(final JsonNode oAuthInputConfigurationFromDB, final JsonNode oAuthInputConfigurationFromInput) {
    final ObjectNode result = (ObjectNode) Jsons.emptyObject();

    oAuthInputConfigurationFromInput.fields().forEachRemaining(entry -> {
      final String k = entry.getKey();
      final JsonNode v = entry.getValue();

      // Note: This does not currently handle replacing masked secrets within nested objects.
      if (AirbyteSecretConstants.SECRETS_MASK.equals(v.textValue())) {
        if (oAuthInputConfigurationFromDB.has(k)) {
          result.set(k, oAuthInputConfigurationFromDB.get(k));
        } else {
          LOGGER.warn("Missing the key {} in the config store in DB", k);
        }
      } else {
        result.set(k, v);
      }
    });

    return result;
  }

  @VisibleForTesting
  JsonNode getOAuthInputConfiguration(final JsonNode hydratedSourceConnectionConfiguration, final Map<String, String> pathsToGet) {
    final Map<String, JsonNode> result = new HashMap<>();
    pathsToGet.forEach((k, v) -> {
      final Optional<JsonNode> configValue = JsonPaths.getSingleValue(hydratedSourceConnectionConfiguration, v);
      if (configValue.isPresent()) {
        result.put(k, configValue.get());
      } else {
        LOGGER.warn("Missing the key {} from the config stored in DB", k);
      }
    });

    return Jsons.jsonNode(result);
  }

  /**
   * Given an OAuth response, writes a secret and returns the secret Coordinate in the appropriate
   * format.
   * <p>
   * Unlike our regular source creation flow, the OAuth credentials created and stored this way will
   * be stored in a singular secret as a string. When these secrets are used, the user will be
   * expected to use the specification to rehydrate the connection configuration with the secret
   * values prior to saving a source/destination.
   * <p>
   * The singular secret was chosen to optimize UX for public API consumers (passing them one secret
   * to keep track of > passing them a set of secrets).
   * <p>
   * See https://github.com/airbytehq/airbyte/pull/22151#discussion_r1104856648 for full discussion.
   */
  public CompleteOAuthResponse writeOAuthResponseSecret(final UUID workspaceId, final CompleteOAuthResponse payload) {

    try {
      final String payloadString = Jackson.getObjectMapper().writeValueAsString(payload);
      final SecretCoordinate secretCoordinate = secretsRepositoryWriter.storeSecret(generateOAuthSecretCoordinate(workspaceId), payloadString);
      return mapToCompleteOAuthResponse(Map.of("secretId", secretCoordinate.getFullCoordinate()));

    } catch (final JsonProcessingException e) {
      throw new RuntimeException("Json object could not be written to string.", e);
    }
  }

  /**
   * Generate OAuthSecretCoordinates. Always assume V1 and do not support secret updates
   */
  private SecretCoordinate generateOAuthSecretCoordinate(final UUID workspaceId) {
    final String coordinateBase = SecretsHelpers.getCoordinatorBase("airbyte_oauth_workspace_", workspaceId, UUID::randomUUID);
    return new SecretCoordinate(coordinateBase, 1);
  }

}
