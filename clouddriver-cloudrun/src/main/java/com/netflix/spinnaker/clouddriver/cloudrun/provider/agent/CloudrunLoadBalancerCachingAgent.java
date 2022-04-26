/*
 * Copyright 2022 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.cloudrun.provider.agent;

import static com.netflix.spinnaker.cats.agent.AgentDataType.Authority.AUTHORITATIVE;
import static com.netflix.spinnaker.clouddriver.cloudrun.cache.Keys.Namespace.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.run.v1.CloudRun;
import com.google.api.services.run.v1.model.Service;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.cats.agent.AgentDataType;
import com.netflix.spinnaker.cats.agent.CacheResult;
import com.netflix.spinnaker.cats.agent.DefaultCacheResult;
import com.netflix.spinnaker.cats.cache.CacheData;
import com.netflix.spinnaker.cats.cache.DefaultCacheData;
import com.netflix.spinnaker.cats.provider.ProviderCache;
import com.netflix.spinnaker.clouddriver.cache.OnDemandAgent;
import com.netflix.spinnaker.clouddriver.cache.OnDemandMetricsSupport;
import com.netflix.spinnaker.clouddriver.cache.OnDemandMetricsSupportable;
import com.netflix.spinnaker.clouddriver.cache.OnDemandType;
import com.netflix.spinnaker.clouddriver.cloudrun.CloudrunCloudProvider;
import com.netflix.spinnaker.clouddriver.cloudrun.cache.Keys;
import com.netflix.spinnaker.clouddriver.cloudrun.model.CloudrunLoadBalancer;
import com.netflix.spinnaker.clouddriver.cloudrun.provider.view.MutableCacheData;
import com.netflix.spinnaker.clouddriver.cloudrun.security.CloudrunNamedAccountCredentials;
import groovy.util.logging.Slf4j;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class CloudrunLoadBalancerCachingAgent extends AbstractCloudrunCachingAgent
    implements OnDemandAgent {
  private final OnDemandMetricsSupport metricsSupport;
  private final String project = getCredentials().getProject();

  public CloudrunLoadBalancerCachingAgent(
      String accountName,
      CloudrunNamedAccountCredentials credentials,
      ObjectMapper objectMapper,
      Registry registry) {
    super(accountName, objectMapper, credentials);
    this.metricsSupport =
        new OnDemandMetricsSupport(
            registry, this, CloudrunCloudProvider.ID + ":" + OnDemandType.LoadBalancer);
  }

  @Override
  public String getAgentType() {
    return getAccountName() + "/" + getSimpleName();
  }

  @Override
  public Collection<AgentDataType> getProvidedDataTypes() {
    return Set.of(AUTHORITATIVE.forType(LOAD_BALANCERS.getNs()));
  }

  @Override
  public CacheResult loadData(ProviderCache providerCache) {
    long start = System.currentTimeMillis();
    List<Service> services =
        loadServices().stream()
            .filter(service -> !shouldIgnoreLoadBalancer(service.getMetadata().getName()))
            .collect(Collectors.toList());
    List<CacheData> evictFromOnDemand = new ArrayList<>();
    List<CacheData> keepInOnDemand = new ArrayList<>();
    List<String> loadBalancerKeys =
        services.stream()
            .map(
                service ->
                    Keys.getLoadBalancerKey(
                        getCredentials().getName(), service.getMetadata().getName()))
            .collect(Collectors.toList());
    providerCache
        .getAll(ON_DEMAND.getNs(), loadBalancerKeys)
        .forEach(
            onDemandEntry -> {
              String cacheTime = (String) onDemandEntry.getAttributes().get("cacheTime");
              String processedCount = (String) onDemandEntry.getAttributes().get("processedCount");
              if (cacheTime != null
                  && Long.parseLong(cacheTime) < start
                  && processedCount != null
                  && Integer.parseInt(processedCount) > 0) {
                evictFromOnDemand.add(onDemandEntry);
              } else {
                keepInOnDemand.add(onDemandEntry);
              }
            });

    Map<String, CacheData> onDemandMap = new HashMap<>();
    keepInOnDemand.forEach(cacheData -> onDemandMap.put(cacheData.getId(), cacheData));
    List<String> onDemandEvict =
        evictFromOnDemand.stream().map(CacheData::getId).collect(Collectors.toList());
    CacheResult cacheResult = buildCacheResult(services, onDemandMap, onDemandEvict, start);

    cacheResult
        .getCacheResults()
        .get(ON_DEMAND.getNs())
        .forEach(
            onDemandEntry -> {
              onDemandEntry.getAttributes().put("processedTime", System.currentTimeMillis());
              Object processedCountObj = onDemandEntry.getAttributes().get("processedCount");
              int processedCount = 0;
              if (processedCountObj != null) {
                processedCount = (int) processedCountObj;
              }
              onDemandEntry.getAttributes().put("processedCount", processedCount + 1);
            });
    return cacheResult;
  }

  @Override
  public String getOnDemandAgentType() {
    return getAgentType() + "-onDemand";
  }

  @Override
  public OnDemandMetricsSupportable getMetricsSupport() {
    return metricsSupport;
  }

  private String getRegion(String loadbalancername) {
    Service service = loadService(loadbalancername);
    if (service != null) {
      return service.getMetadata().getLabels().get("cloud.googleapis.com/location");
    }
    return null;
  }

  @Override
  public boolean handles(OnDemandType type, String cloudProvider) {
    return cloudProvider.equals(CloudrunCloudProvider.ID)
        && (type.equals(OnDemandType.LoadBalancer));
  }

  @Override
  public OnDemandResult handle(ProviderCache providerCache, Map<String, ?> data) {
    if (!data.containsKey("loadBalancerName") || data.get("account") != getAccountName()) {
      return null;
    }
    String loadBalancerName = data.get("loadBalancerName").toString();

    if (shouldIgnoreLoadBalancer(loadBalancerName)) {
      return null;
    }
    Service service = metricsSupport.readData(() -> loadService(loadBalancerName));
    CacheResult result =
        metricsSupport.transformData(
            () ->
                buildCacheResult(
                    new ArrayList<Service>(List.of(service)),
                    new HashMap<String, CacheData>(),
                    new ArrayList<String>(),
                    Long.MAX_VALUE));
    String loadBalancerKey = Keys.getLoadBalancerKey(getAccountName(), loadBalancerName);
    try {
      String jsonResult = getObjectMapper().writeValueAsString(result.getCacheResults());
      if (result.getCacheResults().values().stream()
          .flatMap(Collection::stream)
          .collect(Collectors.toList())
          .isEmpty()) {
        providerCache.evictDeletedItems(ON_DEMAND.getNs(), List.of(loadBalancerKey));
      } else {
        metricsSupport.onDemandStore(
            () -> {
              CacheData cacheData =
                  new DefaultCacheData(
                      loadBalancerKey,
                      10 * 60, // ttl is 10 minutes.
                      Map.of(
                          "cacheTime",
                          System.currentTimeMillis(),
                          "cacheResults",
                          jsonResult,
                          "processedCount",
                          0,
                          "processedTime",
                          null),
                      Map.of());
              providerCache.putCacheData(ON_DEMAND.getNs(), cacheData);
              return null;
            });
      }
      Map<String, Collection<String>> evictions = Map.of();
      if (service.isEmpty()) {
        evictions =
            new HashMap<>() {
              {
                put(LOAD_BALANCERS.getNs(), List.of(loadBalancerKey));
              }
            };
      }
      logger.info("On demand cache refresh (data: {}) succeeded", data);
      return new OnDemandResult(getOnDemandAgentType(), result, evictions);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  public CacheResult buildCacheResult(
      List<Service> services,
      Map<String, CacheData> onDemandKeep,
      List<String> onDemandEvict,
      Long start) {
    logger.info("Describing items in {}", getAgentType());

    Map<String, CacheData> cachedLoadBalancers = new HashMap<>();

    services.forEach(
        service -> {
          String loadBalancerName = service.getMetadata().getName();
          String loadBalancerKey = Keys.getLoadBalancerKey(getAccountName(), loadBalancerName);

          if (onDemandKeep != null) {
            CacheData onDemandData = onDemandKeep.get(loadBalancerKey);

            if ((onDemandData != null)
                && Long.parseLong(onDemandData.getAttributes().get("cacheTime").toString())
                    >= start) {
              Map<String, List<CacheData>> cacheResults;
              try {
                cacheResults =
                    getObjectMapper()
                        .readValue(
                            onDemandData.getAttributes().get("cacheResults").toString(),
                            new TypeReference<>() {});
              } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
              }
              cache(cacheResults, LOAD_BALANCERS.getNs(), cachedLoadBalancers);
            }
          } else {
            MutableCacheData LoadBalancerCacheData = new MutableCacheData(loadBalancerName);
            LoadBalancerCacheData.getAttributes().put("name", loadBalancerName);
            LoadBalancerCacheData.getAttributes()
                .put(
                    "loadBalancer",
                    new CloudrunLoadBalancer(
                        service, getAccountName(), getRegion(loadBalancerName)));
          }
        });

    logger.info("Caching {} load balancers in {}", cachedLoadBalancers.size(), getAgentType());

    return new DefaultCacheResult(
        new HashMap<String, Collection<CacheData>>() {
          {
            put(LOAD_BALANCERS.getNs(), cachedLoadBalancers.values());
            put(ON_DEMAND.getNs(), onDemandKeep.values());
          }
        },
        new HashMap<String, Collection<String>>() {
          {
            put(ON_DEMAND.getNs(), onDemandEvict);
          }
        });
  }

  @Override
  public Collection<Map<String, Object>> pendingOnDemandRequests(ProviderCache providerCache) {

    Collection<Map<String, Object>> requests = new HashSet<>();
    Collection<String> keys =
        providerCache.getIdentifiers(ON_DEMAND.getNs()).stream()
            .filter(
                k -> {
                  Map<String, String> parse = Keys.parse(k);
                  return (parse != null && Objects.equals(parse.get("account"), getAccountName()));
                })
            .collect(Collectors.toSet());

    providerCache
        .getAll(ON_DEMAND.getNs(), keys)
        .forEach(
            cacheData -> {
              Map<String, String> details = Keys.parse(cacheData.getId());
              requests.add(
                  Map.of(
                      "details", details,
                      "moniker", convertOnDemandDetails(details),
                      "cacheTime", cacheData.getAttributes().get("cacheTime"),
                      "processedCount", cacheData.getAttributes().get("processedCount"),
                      "processedTime", cacheData.getAttributes().get("processedTime")));
            });
    return requests;
  }

  @Override
  public String getSimpleName() {
    return CloudrunLoadBalancerCachingAgent.class.getSimpleName();
  }

  private Optional<CloudRun.Namespaces.Services.List> getServicesListRequest(String project) {
    try {
      return Optional.of(
          getCredentials().getCloudRun().namespaces().services().list("namespaces/" + project));
    } catch (IOException e) {
      logger.error("Error in creating request for the method services.list !!! {}", e.getMessage());
      return Optional.empty();
    }
  }

  private List<Service> loadServices() {
    Optional<CloudRun.Namespaces.Services.List> servicesListRequest =
        getServicesListRequest(project);
    if (servicesListRequest.isEmpty()) {
      return new ArrayList<>();
    }
    try {
      return servicesListRequest.get().execute().getItems();
    } catch (IOException e) {
      logger.error("Error executing services.list request. {}", e.getMessage());
      return new ArrayList<>();
    }
  }

  private Service loadService(String loadbalancername) {
    List<Service> services = loadServices();
    List<Service> loadbalancer =
        services.stream()
            .filter(s -> s.getMetadata().getName().equals(loadbalancername))
            .collect(Collectors.toList());
    if (!loadbalancer.isEmpty()) {
      return loadbalancer.get(0);
    } else {
      logger.error("No CloudRun Service found with name {}", loadbalancername);
    }
    return null;
  }
}
