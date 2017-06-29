package com.linkedin.datastream.server.dms;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.commons.lang.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;

import com.linkedin.datastream.common.Datastream;
import com.linkedin.datastream.common.DatastreamAlreadyExistsException;
import com.linkedin.datastream.common.DatastreamMetadataConstants;
import com.linkedin.datastream.common.DatastreamUtils;
import com.linkedin.datastream.common.RestliUtils;
import com.linkedin.datastream.metrics.BrooklinGaugeInfo;
import com.linkedin.datastream.metrics.BrooklinMeterInfo;
import com.linkedin.datastream.metrics.BrooklinMetricInfo;
import com.linkedin.datastream.metrics.DynamicMetricsManager;
import com.linkedin.datastream.server.Coordinator;
import com.linkedin.datastream.server.DatastreamServer;
import com.linkedin.datastream.server.api.connector.DatastreamValidationException;
import com.linkedin.restli.common.HttpStatus;
import com.linkedin.restli.server.CreateResponse;
import com.linkedin.restli.server.PagingContext;
import com.linkedin.restli.server.UpdateResponse;
import com.linkedin.restli.server.annotations.Context;
import com.linkedin.restli.server.annotations.RestLiCollection;
import com.linkedin.restli.server.resources.CollectionResourceTemplate;


/*
 * Resources classes are used by rest.li to process corresponding http request.
 * Note that rest.li will instantiate an object each time it processes a request.
 * So do make it thread-safe when implementing the resources.
 */
@RestLiCollection(name = "datastream", namespace = "com.linkedin.datastream.server.dms")
public class DatastreamResources extends CollectionResourceTemplate<String, Datastream> {
  private static final Logger LOG = LoggerFactory.getLogger(DatastreamResources.class);
  private static final String CLASS_NAME = DatastreamResources.class.getSimpleName();

  private final DatastreamStore _store;
  private final Coordinator _coordinator;
  private final ErrorLogger _errorLogger;

  private static final String UPDATE_CALL = "updateCall";
  private static final String DELETE_CALL = "deleteCall";
  private static final String GET_CALL = "getCall";
  private static final String GET_ALL_CALL = "getAllCall";
  private static final String CREATE_CALL = "createCall";
  private static final String CALL_ERROR = "callError";

  private static AtomicLong _createCallLatencyMs = new AtomicLong(0L);
  private static AtomicLong _deleteCallLatencyMs = new AtomicLong(0L);
  private static final Gauge<Long> CREATE_CALL_LATENCY_MS = () -> _createCallLatencyMs.get();
  private static final Gauge<Long> DELETE_CALL_LATENCY_MS = () -> _deleteCallLatencyMs.get();
  private static final String CREATE_CALL_LATENCY_MS_STRING = "createCallLatencyMs";
  private static final String DELETE_CALL_LATENCY_MS_STRING = "deleteCallLatencyMs";

  private final DynamicMetricsManager _dynamicMetricsManager;

  public DatastreamResources(DatastreamServer datastreamServer) {
    _store = datastreamServer.getDatastreamStore();
    _coordinator = datastreamServer.getCoordinator();
    _errorLogger = new ErrorLogger(LOG, _coordinator.getInstanceName());

    _dynamicMetricsManager = DynamicMetricsManager.getInstance();
    _dynamicMetricsManager.registerMetric(getClass(), CREATE_CALL_LATENCY_MS_STRING, CREATE_CALL_LATENCY_MS);
    _dynamicMetricsManager.registerMetric(getClass(), DELETE_CALL_LATENCY_MS_STRING, DELETE_CALL_LATENCY_MS);
  }

  @Override
  public UpdateResponse update(String key, Datastream datastream) {
    _dynamicMetricsManager.createOrUpdateMeter(getClass(), UPDATE_CALL, 1);
    // TODO: behavior of updating a datastream is not fully defined yet; block this method for now
    return new UpdateResponse(HttpStatus.S_405_METHOD_NOT_ALLOWED);
  }

  @Override
  public UpdateResponse delete(String datastreamName) {
    if (null == _store.getDatastream(datastreamName)) {
      _errorLogger.logAndThrowRestLiServiceException(HttpStatus.S_404_NOT_FOUND,
          "Datastream requested to be deleted does not exist: " + datastreamName);
    }

    try {
      LOG.info("Delete datastream called for datastream " + datastreamName);

      _dynamicMetricsManager.createOrUpdateMeter(getClass(), DELETE_CALL, 1);
      Instant startTime = Instant.now();
      _store.deleteDatastream(datastreamName);
      _deleteCallLatencyMs.set(Duration.between(startTime, Instant.now()).toMillis());

      return new UpdateResponse(HttpStatus.S_200_OK);
    } catch (Exception e) {
      _dynamicMetricsManager.createOrUpdateMeter(getClass(), CALL_ERROR, 1);
      _errorLogger.logAndThrowRestLiServiceException(HttpStatus.S_500_INTERNAL_SERVER_ERROR,
        "Delete failed for datastream: " + datastreamName, e);
    }

    return null;
  }

  // Returning null will automatically trigger a 404 Not Found response
  @Override
  public Datastream get(String name) {
    try {
      LOG.info(String.format("Get datastream called for datastream %s", name));
      _dynamicMetricsManager.createOrUpdateMeter(getClass(), GET_CALL, 1);
      return _store.getDatastream(name);
    } catch (Exception e) {
      _dynamicMetricsManager.createOrUpdateMeter(getClass(), CALL_ERROR, 1);
      _errorLogger.logAndThrowRestLiServiceException(HttpStatus.S_500_INTERNAL_SERVER_ERROR,
        "Get datastream failed for datastream: " + name, e);
    }

    return null;
  }

  @SuppressWarnings("deprecated")
  @Override
  public List<Datastream> getAll(@Context PagingContext pagingContext) {
    try {
      LOG.info(String.format("Get all datastreams called with paging context %s", pagingContext));
      _dynamicMetricsManager.createOrUpdateMeter(getClass(), GET_ALL_CALL, 1);
      List<Datastream> ret = RestliUtils.withPaging(_store.getAllDatastreams(), pagingContext).map(_store::getDatastream)
        .filter(stream -> stream != null).collect(Collectors.toList());
      if (LOG.isDebugEnabled()) {
        LOG.debug("Result collected for getAll: %s" + ret);
      }
      return ret;
    } catch (Exception e) {
      _dynamicMetricsManager.createOrUpdateMeter(getClass(), CALL_ERROR, 1);
      _errorLogger
        .logAndThrowRestLiServiceException(HttpStatus.S_500_INTERNAL_SERVER_ERROR, "Get all datastreams failed.", e);
    }

    return Collections.emptyList();
  }

  @Override
  public CreateResponse create(Datastream datastream) {
    try {
      LOG.info(String.format("Create datastream called with datastream %s", datastream));
      if (LOG.isDebugEnabled()) {
        LOG.debug("Handling request on object: %s thread: %s", this, Thread.currentThread());
      }

      _dynamicMetricsManager.createOrUpdateMeter(getClass(), CREATE_CALL, 1);

      // rest.li has done this mandatory field check in the latest version.
      // Just in case we roll back to an earlier version, let's do the validation here anyway
      DatastreamUtils.validateNewDatastream(datastream);
      Validate.isTrue(datastream.hasName(), "Must specify name of Datastream!");
      Validate.isTrue(datastream.hasConnectorName(), "Must specify connectorType!");
      Validate.isTrue(datastream.hasSource(), "Must specify source of Datastream!");
      Validate.isTrue(datastream.hasSource(), "Must specify source of Datastream!");
      Validate.isTrue(datastream.hasMetadata()
          && datastream.getMetadata().containsKey(DatastreamMetadataConstants.OWNER_KEY),
          "Must specify owner of Datastream");

      if (datastream.hasDestination() && datastream.getDestination().hasConnectionString()) {
        datastream.getMetadata().put(DatastreamMetadataConstants.IS_USER_MANAGED_DESTINATION_KEY, "true");
      }

      Instant startTime = Instant.now();

      LOG.debug("Sanity check is finished, initializing datastream");

      _coordinator.initializeDatastream(datastream);

      LOG.debug("Persisting initialized datastream to zookeeper: %s",  datastream);

      _store.createDatastream(datastream.getName(), datastream);

      Duration delta = Duration.between(startTime, Instant.now());
      _createCallLatencyMs.set(delta.toMillis());

      LOG.debug("Datastream persisted to zookeeper, total time used: %dms", delta.toMillis());
      return new CreateResponse(datastream.getName(), HttpStatus.S_201_CREATED);
    } catch (IllegalArgumentException e) {
      _dynamicMetricsManager.createOrUpdateMeter(getClass(), CALL_ERROR, 1);
      _errorLogger.logAndThrowRestLiServiceException(HttpStatus.S_400_BAD_REQUEST,
          "Invalid input params for create request", e);
    } catch (DatastreamValidationException e) {
      _dynamicMetricsManager.createOrUpdateMeter(getClass(), CALL_ERROR, 1);
      _errorLogger.logAndThrowRestLiServiceException(HttpStatus.S_400_BAD_REQUEST,
          "Failed to initialize Datastream: ", e);
    } catch (DatastreamAlreadyExistsException e) {
      _dynamicMetricsManager.createOrUpdateMeter(getClass(), CALL_ERROR, 1);
      _errorLogger.logAndThrowRestLiServiceException(HttpStatus.S_409_CONFLICT,
          "Datastream with the same name already exists: " + datastream, e);
    } catch (Exception e) {
      _dynamicMetricsManager.createOrUpdateMeter(getClass(), CALL_ERROR, 1);
      _errorLogger.logAndThrowRestLiServiceException(HttpStatus.S_500_INTERNAL_SERVER_ERROR,
          "Unexpected error during datastream creation: " + datastream, e);
    }

    // Should never get here because we throw on any errors
    return null;
  }

  public static List<BrooklinMetricInfo> getMetricInfos() {
    List<BrooklinMetricInfo> metrics = new ArrayList<>();

    metrics.add(new BrooklinMeterInfo(MetricRegistry.name(CLASS_NAME, UPDATE_CALL)));
    metrics.add(new BrooklinMeterInfo(MetricRegistry.name(CLASS_NAME, DELETE_CALL)));
    metrics.add(new BrooklinMeterInfo(MetricRegistry.name(CLASS_NAME, GET_CALL)));
    metrics.add(new BrooklinMeterInfo(MetricRegistry.name(CLASS_NAME, GET_ALL_CALL)));
    metrics.add(new BrooklinMeterInfo(MetricRegistry.name(CLASS_NAME, CREATE_CALL)));
    metrics.add(new BrooklinMeterInfo(MetricRegistry.name(CLASS_NAME, CALL_ERROR)));

    metrics.add(new BrooklinGaugeInfo(MetricRegistry.name(CLASS_NAME, CREATE_CALL_LATENCY_MS_STRING)));
    metrics.add(new BrooklinGaugeInfo(MetricRegistry.name(CLASS_NAME, DELETE_CALL_LATENCY_MS_STRING)));

    return Collections.unmodifiableList(metrics);
  }
}
