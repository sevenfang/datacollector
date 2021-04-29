/*
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.util.http;

import com.google.common.collect.ImmutableMap;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.PushSource;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.Stage;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.OnRecordErrorException;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.lib.http.AuthenticationFailureException;
import com.streamsets.pipeline.lib.http.Errors;
import com.streamsets.pipeline.lib.http.Groups;
import com.streamsets.pipeline.lib.http.oauth2.OAuth2ConfigBean;
import com.streamsets.pipeline.lib.parser.DataParser;
import com.streamsets.pipeline.lib.parser.DataParserFactory;
import com.streamsets.pipeline.lib.util.ExceptionUtils;
import com.streamsets.pipeline.lib.util.ThreadUtil;
import com.streamsets.pipeline.stage.origin.http.HttpResponseActionConfigBean;
import com.streamsets.pipeline.stage.origin.http.ResponseAction;
import com.streamsets.pipeline.stage.origin.restservice.RestServiceReceiver;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static com.streamsets.pipeline.lib.http.Errors.HTTP_21;
import static com.streamsets.pipeline.lib.http.Errors.HTTP_22;

public abstract class HttpStageUtil {

  private static final Logger LOG = LoggerFactory.getLogger(HttpStageUtil.class);

  public static final String CONTENT_TYPE_HEADER = "Content-Type";
  public static final String DEFAULT_CONTENT_TYPE = "application/json";

  // Empty constructor
  HttpStageUtil(){
  }

  public static Object getFirstHeaderIgnoreCase(String name, MultivaluedMap<String, Object> headers) {
    for (final Map.Entry<String, List<Object>> headerEntry : headers.entrySet()) {
      if (name.equalsIgnoreCase(headerEntry.getKey())) {
        if (headerEntry.getValue() != null && !headerEntry.getValue().isEmpty()) {
          return headerEntry.getValue().get(0);
        }
        break;
      }
    }
    return null;
  }

  public static String getContentTypeWithDefault(MultivaluedMap<String, Object> headers, String defaultType) {
    final Object contentTypeObj = getFirstHeaderIgnoreCase(CONTENT_TYPE_HEADER, headers);
    if (contentTypeObj != null) {
      return contentTypeObj.toString();
    } else {
      return defaultType;
    }
  }

  public static boolean getNewOAuth2Token(OAuth2ConfigBean oauth2, Client client) {
    LOG.info("OAuth2 Authentication token has likely expired. Fetching new token.");
    try {
      oauth2.reInit(client);
      return true;
    } catch (AuthenticationFailureException ex) {
      LOG.error("OAuth2 Authentication failed", ex);
      throw new StageException(HTTP_21);
    } catch (IOException ex) {
      LOG.error("OAuth2 Authentication Response does not contain access token", ex);
      throw new StageException(HTTP_22);
    }
  }

  public static String getContentType(DataFormat dataFormat) {
    switch (dataFormat) {
      case TEXT:
        return MediaType.TEXT_PLAIN;
      case JSON:
      case SDC_JSON:
        return MediaType.APPLICATION_JSON;
      default:
        // Default is binary blob
        return MediaType.APPLICATION_OCTET_STREAM;
    }
  }

  public static Record createEnvelopeRecord(
      PushSource.Context context,
      DataParserFactory parserFactory,
      List<Record> successRecords,
      List<Record> errorRecords,
      int statusCode,
      String errorMessage,
      DataFormat dataFormat
  ) {
    Record envelopeRecord = context.createRecord("envelopeRecord");
    LinkedHashMap<String,Field> envelopeRecordVal = new LinkedHashMap<>();
    envelopeRecordVal.put("httpStatusCode", Field.create(statusCode));
    if (errorMessage != null) {
      envelopeRecordVal.put("errorMessage", Field.create(errorMessage));
    }
    envelopeRecordVal.put("data", Field.create(convertRecordsToFields(parserFactory, successRecords)));
    envelopeRecordVal.put("error", Field.create(convertRecordsToFields(parserFactory, errorRecords)));
    if (dataFormat.equals(DataFormat.XML)) {
      // XML format requires single key map at the root
      envelopeRecord.set(Field.create(ImmutableMap.of("response", Field.createListMap(envelopeRecordVal))));
    } else {
      envelopeRecord.set(Field.createListMap(envelopeRecordVal));
    }
    return envelopeRecord;
  }

  private static List<Field> convertRecordsToFields(DataParserFactory parserFactory, List<Record> recordList) {
    List<Field> fieldList = new ArrayList<>();
    recordList.forEach(record -> {
      String rawDataHeader = record.getHeader().getAttribute(RestServiceReceiver.RAW_DATA_RECORD_HEADER_ATTR_NAME);
      if (StringUtils.isNotEmpty(rawDataHeader)) {
        String rawData = record.get().getValueAsString();
        try (DataParser parser = parserFactory.getParser("rawData", rawData)) {
          Record parsedRecord = parser.parse();
          while (parsedRecord != null) {
            fieldList.add(parsedRecord.get());
            parsedRecord = parser.parse();
          }
        } catch (Exception e) {
          // If fails to parse data, add raw data from response to envelope record
          fieldList.add(record.get());
          LOG.debug("Failed to parse rawPayloadRecord from Response sink", e);
        }
      } else {
        fieldList.add(record.get());
      }
    });
    return fieldList;
  }

  /**
   * @param issues existing list of config issues to add validation errors to
   * @param context the stage context
   * @param responseStatusActionConfigs the list of status->action configs (from stage config)
   * @param statusToActionConfigs a map of status->action configs, keyed by status code (will be updated
   *   by this method)
   * @param configName the name of the responseStatusActionConfigs config field within the stage
   * @return a list of config issues (unmodified from issues param if no errors)
   */
  public static List<Stage.ConfigIssue> validateStatusActionConfigs(
      List<Stage.ConfigIssue> issues,
      Stage.Context context,
      List<? extends HttpResponseActionConfigBean> responseStatusActionConfigs,
      Map<Integer, HttpResponseActionConfigBean> statusToActionConfigs,
      String configName
    ) {

    if (responseStatusActionConfigs != null) {
      final String cfgName = configName;
      final EnumSet<ResponseAction> backoffRetries = EnumSet.of(
          ResponseAction.RETRY_EXPONENTIAL_BACKOFF,
          ResponseAction.RETRY_LINEAR_BACKOFF
      );

      for (HttpResponseActionConfigBean actionConfig : responseStatusActionConfigs) {
        final HttpResponseActionConfigBean prevAction = statusToActionConfigs.put(
            actionConfig.getStatusCode(),
            actionConfig
        );

        if (prevAction != null) {
          issues.add(
              context.createConfigIssue(
                  Groups.HTTP.name(),
                  cfgName,
                  Errors.HTTP_17,
                  actionConfig.getStatusCode()
              )
          );
        }
        if (backoffRetries.contains(actionConfig.getAction()) && actionConfig.getBackoffInterval() <= 0) {
          issues.add(
              context.createConfigIssue(
                  Groups.HTTP.name(),
                  cfgName,
                  Errors.HTTP_15
              )
          );
        }
        if (actionConfig.getStatusCode() >= 200 && actionConfig.getStatusCode() < 300) {
          issues.add(
              context.createConfigIssue(
                  Groups.HTTP.name(),
                  cfgName,
                  Errors.HTTP_16
              )
          );
        }
      }
    }

    return issues;
  }

  public static PassthroughAttributes applyResponseAction(
      HttpResponseActionConfigBean actionConf,
      boolean firstOccurence,
      Function<Void, StageException> createConfiguredErrorFunction,
      AtomicInteger retryCount,
      AtomicLong backoffIntervalLinear,
      AtomicLong backoffIntervalExponential
  ) {
    return applyResponseAction(
        actionConf,
        firstOccurence,
        createConfiguredErrorFunction,
        retryCount,
        backoffIntervalLinear,
        backoffIntervalExponential,
        null,
        null,
        false,
        null
    );
  }

  /**
   * Apply an HTTP status response action, based on configuration.
   * Updates stateful variables by using the atomic constructs.
   *
   * @param actionConf the configuration for the action to be performed
   * @param firstOccurrence whether this is the first occurrence of this status or timeout
   * @param createConfiguredErrorFunction a function that produces an exception as per configuration
   * @param retryCount the number of times the request has been retried (will be updated)
   * @param backoffIntervalLinear the linear backoff interval (will be updated)
   * @param backoffIntervalExponential the exponential backoff interval (will be updated)
   * @param inputRecord the input record which led to this action (to be used when generating error records)
   * @param errorRecordMessage a message to be included in the error record, if generated
   * @param forTimeout flag to inform whether to react to an standard HTTP status or to a timeout
   * @param timeoutType type of timeout produced in the last request
   * @return a PassthroughAttributes component providing information about how to proceed
   */
  public static PassthroughAttributes applyResponseAction(
      HttpResponseActionConfigBean actionConf,
      boolean firstOccurrence,
      Function<Void, StageException> createConfiguredErrorFunction,
      AtomicInteger retryCount,
      AtomicLong backoffIntervalLinear,
      AtomicLong backoffIntervalExponential,
      Record inputRecord,
      String errorRecordMessage,
      boolean forTimeout,
      TimeoutType timeoutType
  ) {
    if (firstOccurrence) {
      retryCount.set(0);
    } else {
      retryCount.incrementAndGet();
    }

    if (actionConf.getAction().equals(ResponseAction.ERROR_RECORD) ||
        (!actionConf.getAction().equals(ResponseAction.STAGE_ERROR) &&
         ((actionConf.getMaxNumRetries() > 0 &&
           retryCount.get() > actionConf.getMaxNumRetries()) ||
          (timeoutType != null &&
           timeoutType.equals(TimeoutType.RECORD))))) {
        PassthroughAttributes passthroughAttributes = buildPassthroughAttributes(
          actionConf,
          firstOccurrence ? 0 : retryCount.get() - 1,
          forTimeout,
          timeoutType);
      if (actionConf.isPassRecord()) {
        // It is possible to send both to error and pass through except for batch timeout
        passthroughAttributes.setSendToOutput(true);
      }
      return passthroughAttributes;
    }

    final long backoff = actionConf.getBackoffInterval();
    switch (actionConf.getAction()) {
      case STAGE_ERROR:
        throw createConfiguredErrorFunction.apply(null);
      case ERROR_RECORD:
        break;
      case RETRY_IMMEDIATELY:
        break;
      case RETRY_EXPONENTIAL_BACKOFF:
      case RETRY_LINEAR_BACKOFF:
        long updatedBackoff;
        if (actionConf.getAction() == ResponseAction.RETRY_EXPONENTIAL_BACKOFF) {
          updatedBackoff = firstOccurrence ? backoff : backoffIntervalExponential.get() * 2;
          backoffIntervalExponential.set(updatedBackoff);
        } else {
          updatedBackoff = firstOccurrence ? backoff : backoffIntervalLinear.get() + backoff;
          backoffIntervalLinear.set(updatedBackoff);
        }
        if (LOG.isDebugEnabled()) {
          LOG.debug("Applying backoff for {} ms", updatedBackoff);
        }
        ThreadUtil.sleep(updatedBackoff);
        break;
    }
    return null;
  }

  public static TimeoutType findTimeoutType(Exception eException) {
    Throwable throwable = SocketTimeoutException.class.isInstance(eException) ||
                          TimeoutException.class.isInstance(eException) ?
                          eException :
                          ExceptionUtils.findSpecificCause(eException, SocketTimeoutException.class);
    if (throwable == null) {
      return TimeoutType.NONE;
    }
    if (throwable.getMessage() == null) {
      return TimeoutType.UNKNOWN;
    }

    if (throwable.getMessage().contains(TimeoutType.CONNECTION.getMessage())) {
      return TimeoutType.CONNECTION;
    } else if (throwable.getMessage().contains(TimeoutType.READ.getMessage())) {
      return TimeoutType.READ;
    } else if (throwable.getMessage().contains(TimeoutType.REQUEST.getMessage())) {
      return TimeoutType.REQUEST;
    } else if (throwable.getMessage().contains(TimeoutType.RECORD.getMessage())) {
      return TimeoutType.RECORD;
    } else {
      return TimeoutType.UNKNOWN;
    }
  }

  private static PassthroughAttributes buildPassthroughAttributes (
      HttpResponseActionConfigBean actionConf,
      int retries,
      boolean forTimeout,
      TimeoutType timeoutType) {

    PassthroughAttributes passthroughAttributes = new PassthroughAttributes();
    passthroughAttributes.setError(forTimeout ? Errors.HTTP_102 : Errors.HTTP_101);
    passthroughAttributes.setStatus(actionConf.getStatusCode());
    passthroughAttributes.setAction(actionConf.getAction());
    passthroughAttributes.setRetries(retries);
    passthroughAttributes.setSendToError(true);
    passthroughAttributes.setTimeoutType(timeoutType);
    return passthroughAttributes;
  }
}
