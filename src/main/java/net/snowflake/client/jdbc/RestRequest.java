/*
 * Copyright (c) 2012-2018 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.client.jdbc;

import net.snowflake.client.core.Event;
import net.snowflake.client.core.EventUtil;

import net.snowflake.client.log.SFLogger;
import net.snowflake.client.log.SFLoggerFactory;

import net.snowflake.client.core.HttpUtil;
import net.snowflake.client.util.DecorrelatedJitterBackoff;
import net.snowflake.common.core.SqlState;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This is an abstraction on top of http client.
 * <p>
 * Currently it only has one method for retrying http request execution so that
 * the same logic doesn't have to be replicated at difference places where retry
 * is needed.
 *
 * @author jhuang
 */
public class RestRequest
{
  static final SFLogger logger = SFLoggerFactory.getLogger(RestRequest.class);

  // Request guid per HTTP request
  private static final String SF_REQUEST_GUID = "request_guid";

  // min backoff in milli before we retry due to transient issues
  static private long minBackoffInMilli = 1000;

  // max backoff in milli before we retry due to transient issues
  // we double the backoff after each retry till we reach the max backoff
  static private long maxBackoffInMilli = 16000;

  // retry at least once even if timeout limit has been reached
  static private int MIN_RETRY_COUNT = 1;

  /**
   * Execute an http request with retry logic.
   *
   * @param httpClient          client object used to communicate with other machine
   * @param httpRequest         request object contains all the request information
   * @param retryTimeout        : retry timeout (in seconds)
   * @param injectSocketTimeout : simulate socket timeout
   * @param canceling           canceling flag
   * @param withoutCookies      whether the cookie spec should be set to IGNORE
   *                            or not
   * @param includeRetryParameters whether to include retry parameters in retried
   *                               requests
   * @param includeRequestGuid whether to include request_guid parameter
   * @return HttpResponse Object get from server
   * @throws net.snowflake.client.jdbc.SnowflakeSQLException Request timeout Exception or Illegal State Exception i.e.
   *                                                         connection is already shutdown etc
   */
  static public CloseableHttpResponse execute(
      CloseableHttpClient httpClient,
      HttpRequestBase httpRequest,
      long retryTimeout,
      int injectSocketTimeout,
      AtomicBoolean canceling,
      boolean withoutCookies,
      boolean includeRetryParameters,
      boolean includeRequestGuid) throws SnowflakeSQLException
  {
    CloseableHttpResponse response = null;

    // time the client started attempting to submit request
    final long startTime = System.currentTimeMillis();

    // start time for each request,
    // used for keeping track how much time we have spent
    // due to network issues so that we can compare against the user
    // specified network timeout to make sure we do not retry infinitely
    // when there are transient network/GS issues.
    long startTimePerRequest = startTime;

    // total elapsed time due to transient issues.
    long elapsedMilliForTransientIssues = 0;

    // retry timeout (ms)
    long retryTimeoutInMilliseconds = retryTimeout * 1000;

    // amount of time to wait for backing off before retry
    long backoffInMilli = minBackoffInMilli;

    DecorrelatedJitterBackoff backoff = new DecorrelatedJitterBackoff(
        backoffInMilli, maxBackoffInMilli);

    int retryCount = 0;

    int origSocketTimeout = 0;

    Exception savedEx = null;

    // try request till we get a good response or retry timeout
    while (true)
    {
      logger.debug("Retry count: {}", retryCount);

      try
      {
        // update start time
        startTimePerRequest = System.currentTimeMillis();

        if (withoutCookies)
        {
          httpRequest.setConfig(HttpUtil.getRequestConfigWithoutcookies());
        }

        // for first call, simulate a socket timeout by setting socket timeout
        // to the injected socket timeout value
        if (injectSocketTimeout != 0 && retryCount == 0)
        {
          logger.info("Injecting socket timeout by setting " +
              "socket timeout to {} millisecond ", injectSocketTimeout);
          httpRequest.setConfig(
              HttpUtil.getDefaultRequestConfigWithSocketTimeout(
                  injectSocketTimeout, withoutCookies));
        }

        /*
         * Add retryCount if the first request failed
         * GS can uses the parameter for optimization. Specifically GS
         * will only check metadata database to see if a query has been running
         * for a retry request. This way for the majority of query requests
         * which are not part of retry we don't have to pay the performance
         * overhead of looking up in metadata database.
         */
        URIBuilder builder = new URIBuilder(httpRequest.getURI());
        if (retryCount > 0)
        {
          builder.setParameter(
              "retryCount", String.valueOf(retryCount));
          if (includeRetryParameters)
          {
            builder.setParameter(
                "clientStartTime", String.valueOf(startTime));
          }
        }

        if (includeRequestGuid)
        {
          // Add request_guid for better tracing
          builder.setParameter(SF_REQUEST_GUID, UUID.randomUUID().toString());
        }

        httpRequest.setURI(builder.build());

        response = httpClient.execute(httpRequest);
      }
      catch (Exception ex)
      {
        // if exception is caused by illegal state, e.g shutdown of http client
        // because of closing of connection, stop retrying
        if (ex instanceof IllegalStateException)
        {
          throw new SnowflakeSQLException(ex,
              ErrorCode.INVALID_STATE.getSqlState(),
              ErrorCode.INVALID_STATE.getMessageCode(),
              ex.getMessage());
        }

        savedEx = ex;

        // if the request took more than 5 min (socket timeout) log an error
        if ((System.currentTimeMillis() - startTimePerRequest) > 300000)
        {
          logger.error("HTTP request took longer than 5 min: {} sec",
              (System.currentTimeMillis() - startTimePerRequest) / 1000);
        }

        logger.warn("Exception encountered for: " +
            httpRequest.toString(), ex);
      }
      finally
      {
        // Reset the socket timeout to its original value if it is not the
        // very first iteration.
        if ((injectSocketTimeout != 0) && retryCount == 0)
        {
          httpRequest.setConfig(
              HttpUtil.getDefaultRequestConfigWithSocketTimeout(
                  origSocketTimeout, withoutCookies));
        }
      }

      /*
       * If we got a response and the status code is not one of those
       * transient failures, no more retry
       *
       * SNOW-16385: retry for any 5xx errors
       */
      if (response != null &&
          (response.getStatusLine().getStatusCode() < 500 || // service unavailable
              response.getStatusLine().getStatusCode() >= 600) && // gateway timeout
          response.getStatusLine().getStatusCode() != 408 && // request timeout
          response.getStatusLine().getStatusCode() != 403) // intermittent AWS access issue
      {
        logger.debug("HTTP response code: {}",
            response.getStatusLine().getStatusCode());

        if (response.getStatusLine().getStatusCode() != 200)
        {
          logger.debug("Error response not retriable, " +
                  "HTTP Response Code={}, request={}",
              response.getStatusLine().getStatusCode(),
              httpRequest);
          EventUtil.triggerBasicEvent(
              Event.EventType.NETWORK_ERROR,
              "StatusCode: " + response.getStatusLine().getStatusCode() +
                  ", Reason: " + response.getStatusLine().getReasonPhrase() +
                  ", Request: " + httpRequest.toString(),
              false);

        }

        break;
      }
      else
      {
        if (response != null)
        {
          logger.warn(
              "HTTP response not ok: status code={}, request={}",
              response.getStatusLine().getStatusCode(),
              httpRequest);
        }
        else
        {
          logger.warn("Null response for request={}", httpRequest);
        }

        // get the elapsed time for the last request
        // elapsed in millisecond for last call, used for calculating the
        // remaining amount of time to sleep:
        // (backoffInMilli - elapsedMilliForLastCall)
        long elapsedMilliForLastCall =
            System.currentTimeMillis() - startTimePerRequest;

        // check canceling flag
        if (canceling != null && canceling.get())
        {
          logger.info(
              "Stop retrying since canceling is requested");
          break;
        }

        if (retryTimeoutInMilliseconds > 0)
        {
          // increment total elapsed due to transient issues
          elapsedMilliForTransientIssues += elapsedMilliForLastCall;

          // check if the total elapsed time for transient issues has exceeded
          // the retry timeout and we retry at least the min, if so, we will not
          // retry
          if (elapsedMilliForTransientIssues > retryTimeoutInMilliseconds &&
              retryCount >= MIN_RETRY_COUNT)
          {
            logger.error(
                "Stop retrying since elapsed time due to network " +
                    "issues has reached timeout. " +
                    "Elapsed={}(ms), timeout={}(ms)",
            elapsedMilliForTransientIssues, retryTimeoutInMilliseconds);

            // rethrow the timeout exception
            if (response == null && savedEx != null)
            {
              throw new SnowflakeSQLException(SqlState.IO_ERROR,
                  ErrorCode.NETWORK_ERROR.getMessageCode(),
                  "Exception encountered for HTTP request: " +
                      savedEx.getMessage());
            }
            // no more retry
            break;
          }
        }

        logger.debug("Retrying request: {}", httpRequest);

        // sleep for backoff - elapsed amount of time
        if (backoffInMilli > elapsedMilliForLastCall)
        {
          try
          {
            logger.debug("sleeping in {}(ms)", backoffInMilli);
            Thread.sleep(backoffInMilli);
            elapsedMilliForTransientIssues += backoffInMilli;
            backoffInMilli = backoff.nextSleepTime(backoffInMilli);
          }
          catch (InterruptedException ex1)
          {
            logger.debug(
                "Backoff sleep before retrying login got interrupted");
          }
        }

        // release connection before retry
        httpRequest.releaseConnection();

        retryCount++;
      }
    }

    if (response == null)
    {
      logger.error("Returning null response for request: {}",
          httpRequest);
    }
    else if (response.getStatusLine().getStatusCode() != 200)
    {
      logger.error(
          "Error response: HTTP Response code={}, request={}",
          response.getStatusLine().getStatusCode(),
          httpRequest);
    }
    return response;
  }
}
