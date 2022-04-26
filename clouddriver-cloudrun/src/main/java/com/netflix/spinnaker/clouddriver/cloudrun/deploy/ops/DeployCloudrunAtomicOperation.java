/*
 * Copyright 2022 OpsMx Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.cloudrun.deploy.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.api.services.run.v1.model.*;
import com.google.common.base.CaseFormat;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.clouddriver.cloudrun.CloudrunJobExecutor;
import com.netflix.spinnaker.clouddriver.cloudrun.deploy.description.DeployCloudrunDescription;
import com.netflix.spinnaker.clouddriver.cloudrun.deploy.exception.CloudrunOperationException;
import com.netflix.spinnaker.clouddriver.cloudrun.model.CloudrunService;
import com.netflix.spinnaker.clouddriver.data.task.Task;
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository;
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class DeployCloudrunAtomicOperation implements AtomicOperation<DeploymentResult> {

  private static final String BASE_PHASE = "DEPLOY";

  private static final Logger log = LoggerFactory.getLogger(DeployCloudrunAtomicOperation.class);

  private static Task getTask() {
    return TaskRepository.threadLocalTask.get();
  }

  @Autowired Registry registry;

  @Autowired CloudrunJobExecutor jobExecutor;

  DeployCloudrunDescription description;

  public DeployCloudrunAtomicOperation(DeployCloudrunDescription description) {
    this.description = description;
  }

  public String deploy(String repositoryPath) {
    String project = description.getCredentials().getProject();
    String applicationDirectoryRoot = description.getApplicationDirectoryRoot();
    List<String> configFiles = description.getConfigFiles();
    List<String> modConfigFiles = insertSpinnakerApplication(configFiles);
    List<String> writtenFullConfigFilePaths =
        writeConfigFiles(modConfigFiles, repositoryPath, applicationDirectoryRoot);
    String versionName = getVersionName(writtenFullConfigFilePaths);
    String region = description.getRegion();

    List<String> deployCommand = new ArrayList<>();
    deployCommand.add("gcloud");
    deployCommand.add("run");
    deployCommand.add("services");
    deployCommand.add("replace");
    deployCommand.add(writtenFullConfigFilePaths.stream().collect(Collectors.joining("")));
    deployCommand.add("--region=" + region);
    deployCommand.add("--project=" + project);

    String success = "false";
    getTask().updateStatus(BASE_PHASE, "Deploying version " + versionName + "...");
    try {
      jobExecutor.runCommand(deployCommand);
      success = "true";
    } catch (Exception e) {
      throw new CloudrunOperationException(
          "Failed to deploy to Cloud Run with command "
              + deployCommand
              + "exception "
              + e.getMessage());
    } finally {
      deleteFiles(writtenFullConfigFilePaths);
    }
    getTask().updateStatus(BASE_PHASE, "Done deploying version " + versionName + "...");
    return versionName;
  }

  private String getVersionName(List<String> writtenFullConfigFilePaths) {

    String versionName = "";
    Service googleService = new Service();
    try {
      String content =
          Files.readString(Paths.get(writtenFullConfigFilePaths.get(0)), StandardCharsets.US_ASCII);
      ObjectMapper yamlReader = new ObjectMapper(new YAMLFactory());
      ObjectMapper objectMapper = new ObjectMapper();
      ObjectMapper jsonWriter = new ObjectMapper();
      CloudrunService cloudrunService = null;
      Object yamlObj = yamlReader.readValue(content, Object.class);
      populateCloudrunRegion(yamlObj);
      String jsonString = jsonWriter.writeValueAsString(yamlObj);
      cloudrunService = objectMapper.readValue(jsonString, CloudrunService.class);
      ObjectMeta metaData = new ObjectMeta();
      ServiceSpec spec = new ServiceSpec();
      googleService.setApiVersion(cloudrunService.getApiVersion());
      googleService.setKind(cloudrunService.getKind());
      setCloudrunServiceMetaData(cloudrunService, metaData, googleService);
      setCloudrunServiceSpecData(cloudrunService, spec, googleService);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    log.info(" Version name : " + googleService.getSpec().getTemplate().getMetadata().getName());
    return googleService.getSpec().getTemplate().getMetadata().getName();
  }

  private void populateCloudrunRegion(Object yamlObj) {

    if (yamlObj instanceof Map) {
      Map<String, Object> mapObj = (Map<String, Object>) yamlObj;
      if (mapObj.get("metadata") != null
          && ((LinkedHashMap) mapObj.get("metadata")).get("labels") != null
          && ((LinkedHashMap) ((LinkedHashMap) mapObj.get("metadata")).get("labels"))
                  .get("cloud.googleapis.com/location")
              != null) {
        description.setRegion(
            (String)
                (((LinkedHashMap) ((LinkedHashMap) mapObj.get("metadata")).get("labels"))
                    .get("cloud.googleapis.com/location")));
      }
    }
  }

  private List<String> insertSpinnakerApplication(List<String> configFiles) {

    return configFiles.stream()
        .map(
            (configFile) -> {
              try {
                ObjectMapper yamlReader = new ObjectMapper(new YAMLFactory());
                CloudrunService yamlObj = yamlReader.readValue(configFile, CloudrunService.class);
                if (yamlObj != null) {
                  if (yamlObj.getMetadata() != null) {
                    LinkedHashMap<String, Object> metadataMap = yamlObj.getMetadata();
                    LinkedHashMap<String, Object> annotationsMap =
                        (LinkedHashMap<String, Object>) metadataMap.get("annotations");
                    if (annotationsMap != null) {
                      annotationsMap.put("spinnaker/application", description.getApplication());
                    }
                  }
                }
                return yamlReader.writeValueAsString(yamlObj);
              } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
              }
            })
        .collect(Collectors.toList());
  }

  private void setCloudrunServiceMetaData(
      CloudrunService cloudrunService, ObjectMeta metaData, Service googleService) {

    for (Method method : metaData.getClass().getDeclaredMethods()) {
      final String name = method.getName();
      cloudrunService
          .getMetadata()
          .forEach(
              (k, v) -> {
                String methodName =
                    "set" + CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, k);
                if (name.equals(methodName)) {
                  try {
                    method.invoke(metaData, v);
                  } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                  } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                  }
                }
              });
    }
    googleService.setMetadata(metaData);
  }

  private void setCloudrunServiceSpecData(
      CloudrunService cloudrunService, ServiceSpec spec, Service googleService) {

    RevisionTemplate rt = new RevisionTemplate();
    googleService.setSpec(spec);
    googleService.getSpec().setTemplate(rt);
    List<TrafficTarget> trafficTargetList = new ArrayList<>();
    googleService.getSpec().setTraffic(trafficTargetList);
    for (Method method : spec.getClass().getDeclaredMethods()) {
      final String name = method.getName();
      cloudrunService
          .getSpec()
          .forEach(
              (k, v) -> {
                String methodName =
                    "set" + CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, k);
                if (name.equals(methodName) && name.equals("setTemplate")) {
                  Map<String, Object> templateMap =
                      (Map<String, Object>) cloudrunService.getSpec().get("template");
                  templateMap.forEach(
                      (m, n) -> {
                        if (m.equals("metadata")) {
                          Map<String, Object> metadataMap =
                              (Map<String, Object>) templateMap.get("metadata");
                          ObjectMeta objectMeta = new ObjectMeta();
                          for (Method objMetaMethod : objectMeta.getClass().getDeclaredMethods()) {
                            final String objMetaMethodName = objMetaMethod.getName();
                            metadataMap.forEach(
                                (i, j) -> {
                                  String methodName1 =
                                      "set" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, i);
                                  if (objMetaMethodName.equals(methodName1)) {
                                    try {
                                      objMetaMethod.invoke(objectMeta, j);
                                    } catch (IllegalAccessException e) {
                                      throw new RuntimeException(e);
                                    } catch (InvocationTargetException e) {
                                      throw new RuntimeException(e);
                                    }
                                  }
                                });
                          }
                          rt.setMetadata(objectMeta);
                        } else if (m.equals("spec")) {
                          Map<String, Object> specMap =
                              (Map<String, Object>) templateMap.get("spec");
                          RevisionSpec revisionSpec = new RevisionSpec();
                          for (Method revisionSpecMethod :
                              revisionSpec.getClass().getDeclaredMethods()) {
                            final String revisionSpecMethodName = revisionSpecMethod.getName();
                            specMap.forEach(
                                (i, j) -> {
                                  String specMapMethodName =
                                      "set" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, i);
                                  if (revisionSpecMethodName.equals(specMapMethodName)) {
                                    try {
                                      revisionSpecMethod.invoke(revisionSpec, j);
                                    } catch (IllegalAccessException e) {
                                      throw new RuntimeException(e);
                                    } catch (InvocationTargetException e) {
                                      throw new RuntimeException(e);
                                    }
                                  }
                                });
                          }
                          rt.setSpec(revisionSpec);
                        }
                      });
                } else if (name.equals(methodName) && name.equals("setTraffic")) {
                  Map<String, Object> trafficMap =
                      (Map<String, Object>) cloudrunService.getSpec().get("traffic");
                  TrafficTarget target = new TrafficTarget();
                  for (Method targetMethod : target.getClass().getDeclaredMethods()) {
                    final String targetMethodName = targetMethod.getName();
                    trafficMap.forEach(
                        (i, j) -> {
                          String methodName1 =
                              "set" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, i);
                          if (targetMethodName.equals(methodName1)) {
                            try {
                              targetMethod.invoke(target, j);
                            } catch (IllegalAccessException e) {
                              throw new RuntimeException(e);
                            } catch (InvocationTargetException e) {
                              throw new RuntimeException(e);
                            }
                          }
                        });
                  }
                  trafficTargetList.add(target);
                }
              });
    }
  }

  @Override
  public DeploymentResult operate(List priorOutputs) {

    String baseDir = description.getCredentials().getLocalRepositoryDirectory();
    String directoryPath = getFullDirectoryPath(baseDir);
    String serviceAccount = description.getCredentials().getServiceAccountEmail();
    String deployPath = directoryPath;
    String newVersionName;
    String success = "false";
    getTask().updateStatus(BASE_PHASE, "Initializing creation of version...");
    newVersionName = deploy(deployPath);
    String region = description.getRegion();
    DeploymentResult result = new DeploymentResult();
    StringBuffer sb = new StringBuffer();
    sb.append(region).append(":").append(newVersionName);
    result.setServerGroupNames(Arrays.asList(sb.toString()));
    Map<String, String> namesByRegion = new HashMap<>();
    namesByRegion.put(region, newVersionName);
    result.setServerGroupNameByRegion(namesByRegion);
    log.info(" region in deploy operation : " + region);
    log.info(" new version name in deploy operation : " + newVersionName);
    success = "true";
    return result;
  }

  public static void deleteFiles(List<String> paths) {
    paths.forEach(
        path -> {
          try {
            new File(path).delete();
          } catch (Exception e) {
            throw new CloudrunOperationException("Could not delete config file: ${e.getMessage()}");
          }
        });
  }

  public static List<String> writeConfigFiles(
      List<String> configFiles, String repositoryPath, String applicationDirectoryRoot) {
    if (configFiles == null) {
      return Collections.<String>emptyList();
    } else {
      return configFiles.stream()
          .map(
              (configFile) -> {
                Path path =
                    generateRandomRepositoryFilePath(repositoryPath, applicationDirectoryRoot);
                try {
                  File targetFile = new File(path.toString());
                  FileUtils.writeStringToFile(targetFile, configFile, StandardCharsets.UTF_8);
                } catch (Exception e) {
                  throw new CloudrunOperationException(
                      "Could not write config file: ${e.getMessage()}");
                }
                return path.toString();
              })
          .collect(Collectors.toList());
    }
  }

  public static Path generateRandomRepositoryFilePath(
      String repositoryPath, String applicationDirectoryRoot) {
    String name = UUID.randomUUID().toString();
    String filePath = applicationDirectoryRoot != null ? applicationDirectoryRoot : ".";
    StringBuilder sb = new StringBuilder(name).append(".yaml");
    return Paths.get(repositoryPath, filePath, sb.toString());
  }

  public static String getFullDirectoryPath(String localRepositoryDirectory) {
    return Paths.get(localRepositoryDirectory).toString();
  }
}
